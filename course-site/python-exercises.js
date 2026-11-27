(function () {
  const pattern = /[ \t]*(?:(#[^\r\n]*)|([A-Za-z_]\w*)|(\d+(?:\.\d+)?)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(\*\*|\/\/|[=+\-*/%():,]))/y;
  const precedence = new Map([["+", 1], ["-", 1], ["*", 2], ["/", 2], ["//", 2], ["%", 2], ["**", 3]]);

  function tokenize(source) {
    if (source.length > 4096) throw new Error("Программа слишком длинная.");
    return source.split(/\r?\n/).flatMap((raw, index) => {
      const indent = raw.match(/^[ \t]*/)[0].replace(/\t/g, "    ").length;
      const text = raw.trim();
      const tokens = [];
      let at = 0;
      while (at < text.length) {
        pattern.lastIndex = at;
        const match = pattern.exec(text);
        if (!match) throw new Error("Неподдерживаемый символ в строке " + (index + 1) + ".");
        at = pattern.lastIndex;
        if (match[1]) break;
        tokens.push(match.slice(2).find(value => value !== undefined));
      }
      return tokens.length ? [{ indent, tokens }] : [];
    });
  }

  function parse(lines) {
    let line = 0;

    function block(indent, depth, inFunction) {
      if (depth > 16) throw new Error("Слишком много вложенных блоков.");
      const statements = [];
      while (line < lines.length && lines[line].indent === indent) {
        const row = lines[line++].tokens;
        let at = 0;
        const take = () => row[at++];
        const peek = () => row[at];
        const expect = symbol => {
          if (take() !== symbol) throw new Error("Ожидается «" + symbol + "».");
        };
        const name = () => {
          const value = take();
          if (!value || !/^[A-Za-z_]\w*$/.test(value)) throw new Error("Ожидается имя.");
          return value;
        };
        function items(read) {
          const found = [];
          if (peek() !== ")") {
            do {
              found.push(read());
              if (peek() !== ",") break;
              take();
            } while (peek() !== ")");
          }
          expect(")");
          return found;
        }
        function expression(level = 0, nesting = 0) {
          if (nesting > 64) throw new Error("Слишком сложное выражение.");
          const next = minimum => expression(minimum, nesting + 1);
          const token = take();
          let expr;
          if (token === "-" || token === "+") {
            expr = ["unary", token, next(3)];
          } else if (token === "(") {
            expr = next(0);
            expect(")");
          } else if (token && /^\d/.test(token)) {
            expr = ["literal", Number(token)];
          } else if (token && /^["']/.test(token)) {
            const quotes = { n: "\n", r: "\r", t: "\t" };
            expr = ["literal", token.slice(1, -1).replace(/\\(.)/g, (_, value) => quotes[value] ?? value)];
          } else if (token && /^[A-Za-z_]\w*$/.test(token)) {
            if (peek() === "(") {
              take();
              expr = ["call", token, items(() => next(0))];
            } else {
              expr = ["name", token];
            }
          } else {
            throw new Error("Ожидается выражение.");
          }
          while (precedence.has(peek()) && precedence.get(peek()) >= level) {
            const op = take();
            const right = next(precedence.get(op) + (op === "**" ? 0 : 1));
            expr = ["binary", op, expr, right];
          }
          return expr;
        }
        function nested(insideFunction) {
          expect(":");
          if (peek() !== undefined || line >= lines.length || lines[line].indent <= indent) {
            throw new Error("После двоеточия нужен блок с отступом.");
          }
          return block(lines[line].indent, depth + 1, insideFunction);
        }
        if (peek() === "def") {
          take();
          const identifier = name();
          expect("(");
          const parameters = items(name);
          statements.push(["def", identifier, parameters, nested(true)]);
        } else if (peek() === "for") {
          take();
          const variable = name();
          expect("in");
          const values = expression();
          statements.push(["for", variable, values, nested(inFunction)]);
        } else if (peek() === "return") {
          take();
          if (!inFunction) throw new Error("return используется внутри функции.");
          statements.push(["return", expression()]);
        } else if (row[1] === "=") {
          const variable = name();
          take();
          statements.push(["assign", variable, expression()]);
        } else {
          statements.push(["expression", expression()]);
        }
        if (peek() !== undefined) throw new Error("Лишний текст после выражения.");
      }
      return statements;
    }

    const program = block(0, 0, false);
    if (line !== lines.length) throw new Error("Проверь отступы.");
    return program;
  }

  function run(program, kind) {
    const outputs = [];
    const functions = new Map();
    const globals = new Map();
    let steps = 0;
    function step() {
      if (++steps > 2000) throw new Error("Программа выполняет слишком много шагов.");
    }
    function numeric(value) {
      if (typeof value !== "number" || !Number.isFinite(value)) throw new Error("Требуется конечное число.");
      return value;
    }
    const operations = {
      "+": (a, b) => typeof a === "string" && typeof b === "string" ? a + b : numeric(a) + numeric(b),
      "-": (a, b) => numeric(a) - numeric(b),
      "*": (a, b) => numeric(a) * numeric(b),
      "/": (a, b) => numeric(a) / numeric(b),
      "//": (a, b) => Math.floor(numeric(a) / numeric(b)),
      "%": (a, b) => ((numeric(a) % numeric(b)) + b) % b,
      "**": (a, b) => numeric(a) ** numeric(b)
    };
    const builtins = {
      print(...values) {
        outputs.push(values.join(" "));
      },
      len(value) {
        if (typeof value === "string") return [...value].length;
        if (Array.isArray(value)) return value.length;
        throw new Error("len ожидает строку или диапазон.");
      },
      range(...values) {
        if (values.length < 1 || values.length > 3 || !values.every(Number.isSafeInteger)) {
          throw new Error("range ожидает целые числа.");
        }
        const [start, stop, increment = 1] = values.length === 1 ? [0, values[0]] : values;
        if (!increment) throw new Error("Шаг range не может быть нулём.");
        const numbers = [];
        for (let index = start; increment > 0 ? index < stop : index > stop; index += increment) {
          step();
          numbers.push(index);
        }
        return numbers;
      }
    };

    function evaluate(node, scope) {
      step();
      const [type, value, left, right] = node;
      if (type === "literal") return value;
      if (type === "name") {
        if (scope.has(value)) return scope.get(value);
        if (globals.has(value)) return globals.get(value);
        throw new Error("Неизвестное имя «" + value + "».");
      }
      if (type === "unary") return (value === "-" ? -1 : 1) * numeric(evaluate(left, scope));
      if (type === "binary") {
        const result = operations[value](evaluate(left, scope), evaluate(right, scope));
        return typeof result === "string" && result.length < 16000 ? result : numeric(result);
      }
      const args = left.map(argument => evaluate(argument, scope));
      if (functions.has(value)) {
        const [parameters, body] = functions.get(value);
        if (parameters.length !== args.length) throw new Error("Проверь число аргументов.");
        return execute(body, new Map(parameters.map((name, index) => [name, args[index]])))?.value;
      }
      if (Object.prototype.hasOwnProperty.call(builtins, value)) return builtins[value](...args);
      throw new Error("Неизвестная функция «" + value + "».");
    }

    function execute(statements, scope) {
      for (const [type, name, detail, body] of statements) {
        step();
        if (type === "assign") scope.set(name, evaluate(detail, scope));
        else if (type === "expression") evaluate(name, scope);
        else if (type === "return") return { value: evaluate(name, scope) };
        else if (type === "def") functions.set(name, [detail, body]);
        else {
          const values = evaluate(detail, scope);
          if (!Array.isArray(values) && typeof values !== "string") throw new Error("Цикл ожидает диапазон или строку.");
          for (const value of values) {
            step();
            scope.set(name, value);
            const returned = execute(body, scope);
            if (returned) return returned;
          }
        }
      }
    }

    execute(program, globals);
    if (kind === "square") {
      for (const n of [4, 0, -3, 7]) {
        if (evaluate(["call", "square", [["literal", n]]], globals) !== n * n) {
          throw new Error("Проверь square(" + n + ").");
        }
      }
      return ["16"];
    }
    return outputs;
  }

  window.checkPythonExercise = function (code, expected, kind) {
    try {
      const program = parse(tokenize(code));
      if (kind === "loop" && !program.some(statement => statement[0] === "for")) {
        throw new Error("В этом задании нужен цикл for.");
      }
      const outputs = run(program, kind);
      return { ok: outputs.length === expected.length && outputs.every((value, i) => value === expected[i]), outputs };
    } catch (error) {
      return { ok: false, error: error.message };
    }
  };
})();
