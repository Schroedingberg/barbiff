# Property-Based Testing

This project uses [test.check](https://github.com/clojure/test.check) for property-based testing.

## What is Property-Based Testing?

Instead of writing specific test cases with hardcoded inputs, property-based testing:
1. Generates hundreds of random test cases automatically
2. Verifies that properties (invariants) hold for all inputs
3. When a test fails, automatically shrinks the input to find the minimal failing case

## Running Property Tests

```bash
# Run only property-based tests
clojure -M:dev test-property

# Run all tests (including property tests)
clojure -M:dev test
```

## Test Files

- `test/com/domain/projection_property_test.clj` - Properties for the projection module
- `test/com/domain/setlogging_property_test.clj` - Properties for the set logging module

## Example Properties

### Projection Module

- **`build-state-never-throws`**: The projection function should never throw exceptions for any valid event stream
- **`microcycle-count-matches-started-events`**: The number of microcycles in the state equals the number of `:microcycle-started` events
- **`set-count-preserved`**: Every `:set-logged` event appears exactly once in the final state
- **`every-workout-has-name-and-day`**: All workouts in the state have required fields

### Set Logging Module

- **`active-session-empty-is-false`**: Empty event list means no active session
- **`active-session-with-start-is-true`**: A start event without completion means active session
- **`events-for-set-log-always-ends-with-set-logged`**: Generated event sequences always end with the set-logged event
- **`events-for-set-log-set-has-correct-data`**: The generated set-logged event contains the correct exercise, weight, and reps

## Writing New Properties

```clojure
(defspec my-property-name 100  ; run 100 test cases
  (prop/for-all [input gen-my-input]  ; generate random inputs
    ;; Assert something that should always be true
    (= expected-result (my-function input))))
```

### Generators

Generators create random test data. Common patterns:

```clojure
;; Choose from a list
(gen/elements ["Bench Press" "Squat" "Deadlift"])

;; Generate integers in a range
(gen/choose 1 20)

;; Combine generators
(gen/let [exercise gen-exercise
          weight gen-weight]
  {:exercise exercise :weight weight})

;; Generate collections
(gen/vector gen-set-logged 1 10)  ; vector of 1-10 sets
```

## Benefits

1. **Catches edge cases**: Automatically tests with empty lists, boundary values, etc.
2. **Specification**: Properties document what the code should do
3. **Refactoring confidence**: Properties ensure behavior stays consistent
4. **Minimal failing examples**: When tests fail, test.check shrinks inputs to the simplest case

## Example Output

```
{:result true, :num-tests 100, :seed 1762862566525, :time-elapsed-ms 71, 
 :test-var "build-state-has-microcycles-key"}
```

- `result`: Test passed
- `num-tests`: Ran 100 random test cases
- `seed`: Random seed (use to reproduce failures)
- `time-elapsed-ms`: How long the test took

When a test fails, you'll see:
```
{:shrunk {:smallest [failing-input]}, :failing-size 0, :pass? false}
```

The `:smallest` value shows the minimal input that causes failure.
