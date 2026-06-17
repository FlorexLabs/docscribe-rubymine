# frozen_string_literal: true

# A fully documented class.
class FullyDocumented
  # Returns a greeting for the given name.
  #
  # @param name [String] the name to greet.
  # @return [String] a greeting string.
  def greet(name)
    "Hello, #{name}!"
  end

  # Computes the sum of two numbers.
  #
  # @param a [Integer] the first number.
  # @param b [Integer] the second number.
  # @return [Integer] the sum.
  def add(a, b)
    a + b
  end
end
