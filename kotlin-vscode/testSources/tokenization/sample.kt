// SYNTAX TEST "source.kotlin" "sample testcase"

package example.kotlin.highlighting

import kotlin.math.PI

inline fun getIntValue(): Int = 42

abstract class Shape(val name: String) {
    abstract val area: Double

    open fun displayInfo() {
        println("Shape: $name")
    }

    protected open val protectedProperty: String = "Protected"
}

data class Circle(val radius: Double) : Shape("Circle") {
    override val area: Double
        get() = PI * radius * radius

    override fun displayInfo() {
        super.displayInfo()
        println("Radius: $radius, Area: $area")
    }
}

interface Printable {
    fun printDetails() {
        println("Printing details...")
    }
}

object Singleton : Printable {
    override fun printDetails() {
        println("This is a Singleton object.")
    }

    private val privateProperty: String = "Private"
    internal val internalProperty: String = "Internal"
}

enum class Direction {
    NORTH, SOUTH, EAST, WEST
}

sealed class Result {
    data class Success(val message: String) : Result()
    data class Error(val errorMessage: String) : Result()
    object Loading : Result()
}

fun main() {
    val number: Int = 10
    var name: String = "Kotlin"
    val doubleValue: Double = 3.14
    val binaryNumber = 0b1010
    val hexNumber = 0x1A3F
    val floatNumber = 3.14e-2

    val nullableString: String? = null
    val nonNullableString: String = "Hello"

    val publicProperty: String = "Public"

    println("The number is $number")

    val circle = Circle(5.0)
    circle.displayInfo()

    Singleton.printDetails()

    val direction = Direction.NORTH
    println("Direction is $direction")
    val result: Result = Result.Success("Operation successful")
    when (result) {
        is Result.Success -> println(result.message)
        is Result.Error -> println(result.errorMessage)
        Result.Loading -> println("Loading...")
    }
}

/**
 * This is a test function
 */
fun testFunction(): Int {
    return 42
}

fun controlFlowTests() {
    if (immutableValue.isNotEmpty()) {
        println("Not empty")
    } else {
        println("Empty")
    }

    for (i in 1..10) {
        println(i)
    }
}

annotation class CustomAnnotation

@CustomAnnotation
class AnnotatedClass
val escapedString = "Line break: \n Tab: \t Unicode: \u1234"
val tripleQuotedString = """This is a 
multi-line string"""

fun operatorsTest(a: Int, b: Int): Int {
    return a + b * (a - b) / a % b
}

fun functionWithVarargs(vararg numbers: Int) {
    for (num in numbers) {
        println(num)
    }
}

suspend fun fetchData(): String {
    return "Data fetched"
}

val generic: List<Int> = listOf(1, 2, 3)
