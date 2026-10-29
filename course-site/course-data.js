// course-data.js — единый источник данных курса
window.COURSE_DATA = {
  title: 'Python с нуля',
  version: '1.0',
  updated: '2025-01-15',
  tasks: [
    {
      id: 1, module: 'Модуль 1', type: 'choice', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Какой оператор выводит текст на экран в Python?',
      question: 'Выбери правильный вариант:',
      options: ['echo "Привет"', 'print("Привет")', 'console.log("Привет")', 'printf("Привет")'],
      correct: 1,
      hint: 'Это самая известная функция в Python 😉',
      explain: 'Функция print() — стандартный способ вывода в Python.'
    },
    {
      id: 2, module: 'Модуль 1', type: 'text', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Какой тип данных у числа 3.14?',
      question: 'Впиши название типа (одним словом, латиницей):',
      answer: 'float',
      hint: 'Помни: есть int, а есть...',
      explain: 'Числа с точкой — это float.'
    },
    {
      id: 3, module: 'Модуль 2', type: 'text', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Что выведет выражение: 2 ** 3?',
      question: 'Впиши число:',
      answer: '8',
      hint: '** — это возведение в степень.',
      explain: '2³ = 8.'
    },
    {
      id: 4, module: 'Модуль 2', type: 'choice', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Какой оператор проверяет равенство?',
      question: 'Выбери правильный:',
      options: ['=', '==', '===', 'equals'],
      correct: 1,
      hint: 'Одиночное = — это присваивание.',
      explain: 'В Python = присваивание, == — сравнение.'
    },
    {
      id: 5, module: 'Модуль 2', type: 'code', reward: 25, difficulty: 'medium',
      time: '10 мин',
      title: 'Напиши программу, которая выводит сумму 5 и 7',
      question: 'Напиши код. Он должен вывести <strong>12</strong> в консоль.',
      starter: 'print(5 + 7)',
      hint: 'Используй print() и сложение.',
      explain: 'print(5 + 7) выведет 12.'
    },
    {
      id: 6, module: 'Модуль 3', type: 'text', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Сколько символов в строке "Python"?',
      question: 'Впиши число:',
      answer: '6',
      hint: 'Посчитай буквы: P-y-t-h-o-n.',
      explain: 'len("Python") = 6.'
    },
    {
      id: 7, module: 'Модуль 3', type: 'choice', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Что делает метод .upper() у строки?',
      question: 'Выбери правильный ответ:',
      options: [
        'Делает первую букву заглавной',
        'Переводит строку в верхний регистр',
        'Удаляет пробелы',
        'Разворачивает строку'
      ],
      correct: 1,
      hint: 'upper = верхний.',
      explain: '"hello".upper() → "HELLO"'
    },
    {
      id: 8, module: 'Модуль 3', type: 'code', reward: 25, difficulty: 'medium',
      time: '10 мин',
      title: 'Выведи длину строки "Hello, world!"',
      question: 'Используй len() и print(), чтобы вывести <strong>13</strong>.',
      starter: 'text = "Hello, world!"\nprint(len(text))',
      hint: 'len() возвращает количество символов.',
      explain: 'len("Hello, world!") = 13.'
    },
    {
      id: 9, module: 'Модуль 4', type: 'text', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Какой индекс у последнего элемента [10, 20, 30]?',
      question: 'Впиши число:',
      answer: '2',
      hint: 'Индексация с нуля.',
      explain: '10→0, 20→1, 30→2.'
    },
    {
      id: 10, module: 'Модуль 4', type: 'choice', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Как добавить элемент в конец списка?',
      question: 'Выбери метод:',
      options: ['list.add(x)', 'list.push(x)', 'list.append(x)', 'list.insert(x)'],
      correct: 2,
      hint: 'append = присоединять.',
      explain: 'append() добавляет в конец.'
    },
    {
      id: 11, module: 'Модуль 5', type: 'text', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Какое ключевое слово создаёт функцию?',
      question: 'Впиши одно слово (латиницей):',
      answer: 'def',
      hint: 'Три буквы, начинается с "d".',
      explain: 'def my_func(): — объявление функции.'
    },
    {
      id: 12, module: 'Модуль 5', type: 'code', reward: 25, difficulty: 'hard',
      time: '10 мин',
      title: 'Напиши функцию square(n), возвращающую квадрат числа',
      question: 'Функция должна вернуть n². Проверка вызовет square(4) и ожидает <strong>16</strong>.',
      starter: 'def square(n):\n    return n * n\n\nprint(square(4))',
      hint: 'n * n или n ** 2.',
      explain: 'return n * n — верное решение.'
    },
    {
      id: 13, module: 'Модуль 6', type: 'choice', reward: 10, difficulty: 'medium',
      time: '5 мин',
      title: 'Что такое self в методах класса?',
      question: 'Выбери правильный ответ:',
      options: [
        'Глобальная переменная',
        'Ссылка на текущий экземпляр класса',
        'Ключевое слово для создания объекта',
        'Название модуля'
      ],
      correct: 1,
      hint: 'Он как "я" — ссылается на сам объект.',
      explain: 'self — ссылка на текущий объект.'
    },
    {
      id: 14, module: 'Модуль 6', type: 'text', reward: 10, difficulty: 'easy',
      time: '5 мин',
      title: 'Какое ключевое слово создаёт класс?',
      question: 'Впиши одно слово:',
      answer: 'class',
      hint: 'По-английски "класс".',
      explain: 'class MyClass: — объявление класса.'
    },
    {
      id: 15, module: 'Модуль 7', type: 'code', reward: 50, difficulty: 'hard',
      time: '10 мин',
      title: 'Финальный проект: выведи чётные числа от 0 до 6',
      question: 'Используй цикл, выведи <strong>по одному числу на строку</strong>: 0, 2, 4, 6.',
      starter: 'for i in range(0, 7, 2):\n    print(i)',
      hint: 'range(0, 7, 2) — третий аргумент это шаг.',
      explain: 'range(0, 7, 2) даёт 0, 2, 4, 6.'
    }
  ]
};
