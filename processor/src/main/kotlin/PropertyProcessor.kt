import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import org.monzon.annotation.Property


class PropertyProcessor(private val codeGenerator: CodeGenerator, private val logger: KSPLogger) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(Property::class.qualifiedName!!)
        val unableToProcess = symbols.filterNot { it.validate() }

        symbols.filter { it is KSClassDeclaration && it.validate() }
            .forEach {
                it.accept(Visitor(codeGenerator, logger), Unit)
            }

        return unableToProcess.toList()
    }
}

class Visitor(private val codeGenerator: CodeGenerator, private val logger: KSPLogger) : KSVisitorVoid() {

    private lateinit var className: String
    private lateinit var packageName: String

    @OptIn(KspExperimental::class)
    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val qualifiedName = classDeclaration.qualifiedName?.asString()
        if (!classDeclaration.isDataClass()) {
            logger.error(
                "@Property cannot target non-data class $qualifiedName",
                classDeclaration
            )
            return
        }

        if (qualifiedName == null) {
            logger.error(
                "@Property must target classes with qualified names",
                classDeclaration
            )
            return
        }
        logger.info("class declaration $classDeclaration")

        className = qualifiedName
        packageName = classDeclaration.packageName.asString()


        val hashMap = ClassName("kotlin.collections", "HashMap")
        val hashMapOfAny = hashMap.parameterizedBy(ANY, ANY)

        val extensionBuilder = FunSpec.builder("toMap")
            .receiver(classDeclaration.toClassName())
            .returns(hashMapOfAny)
            .beginControlFlow("val map = HashMap<Any, Any>().apply")

        val properties = classDeclaration.getAllProperties()
        properties.forEach { property ->
            val propertyName = property.simpleName.getShortName()
            if (property.type.resolve().declaration.isAnnotationPresent(Property::class)) {
                // if another data class is present with @Property, just use toMap() as it will
                // be generated by ksp
                extensionBuilder.addStatement("put(\"$propertyName\", $propertyName.toMap())")
            } else {
                extensionBuilder.addStatement("put(\"$propertyName\", $propertyName)")
            }
        }

        extensionBuilder.endControlFlow()
        extensionBuilder.addStatement("return map")

        val fileSpec = FileSpec.builder(
            packageName = packageName,
            fileName = classDeclaration.simpleName.asString() + "Ext"
        ).apply {
            addFunction(
                extensionBuilder.build()
            )
        }.build()

        fileSpec.writeTo(codeGenerator = codeGenerator, aggregating = false)
    }

    private fun KSClassDeclaration.isDataClass() = modifiers.contains(Modifier.DATA)
}