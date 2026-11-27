const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const context = { window: {} };
for (const name of ["course-data.js", "python-exercises.js"]) {
  const file = "course-site/" + name;
  vm.runInNewContext(fs.readFileSync(file, "utf8"), context, { filename: file });
}
const { COURSE_DATA, checkPythonExercise } = context.window;
const solutions = {5:"print(5+7)",8:'print(len("Hello, world!"))',12:"def square(n):\n    return n*n",15:"for i in range(0,7,2):\n    print(i)"};
assert.equal(checkPythonExercise("def square(n):\n    return 16", ["16"], "square").ok, false);
for (const task of COURSE_DATA.tasks.filter(task => task.type === "code")) {
  assert.equal(checkPythonExercise(task.starter, task.codeCheck.output, task.codeCheck.kind).ok, false);
  const result = checkPythonExercise(solutions[task.id], task.codeCheck.output, task.codeCheck.kind);
  assert.equal(result.ok, true, "Exercise " + task.id + ": " + result.error);
}
assert.equal(checkPythonExercise("print(11)", ["12"]).ok, false);
assert.equal(checkPythonExercise("print(5 + 7) # comment", ["12"]).ok, true);
assert.equal(checkPythonExercise("def square(n):\n    return n ** 2", ["16"], "square").ok, true);
assert.equal(checkPythonExercise("for i in range(100000):\n    print(i)", []).ok, false);
assert.equal(checkPythonExercise("print(1 / 0)", []).ok, false);
console.log("Python exercise checks passed.");
