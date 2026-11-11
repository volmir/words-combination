# CAMPAIGN_REPORT — GEN-05

Это оценки фиксированной модели, не измерения аудитории. Лимиты симуляции не ограничивают игрока.

Статус: **exhausted**, installable=False. Проверено кандидатов: 7; выбран №2. Только полный GEN-04 разрешает проверенную копию.

Модель, бюджеты и правило сравнения: [CAMPAIGN_SIMULATION.md](CAMPAIGN_SIMULATION.md), [конфиг](../tools/campaign_simulation_config.json).

Seed 20261111; 500 прогонов на уровень/кандидат; 95% интервалы Уилсона (поточечные, без поправки на множественные сравнения). Сравнение включительное по точечной оценке; интервалы показывают неопределённость, а не меняют пороги.

Калибровочный пул: 7913 слов; среднее p=0.450000; intercept=-5.738107994240; SHA-256 пула `a2af812c5f5eaf31a23a2721d74ca391d8fecd80b4e8446e986d10831c556c95`.

Входной кандидат SHA-256 `bd3ed4ac72c1121baabfbff5855c5fec901cca31887948938fc419dcc9638a50`; выбранный (канонический JSON) `eaef409922a2ed70961fc46eff08d949a13155d2447caa66c6f9603ac0019558`.

| № | Исходник | M/R | Прогоны | P(≥R), 95% ДИ | P(=M), 95% ДИ | Цели: ≥R мин.; M мин.–макс. | Отклонения |
|---|---|---|---|---|---|---|---|
| 1 | реввоенсовет | 1/1 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 95.00%; 70.00%–100.00% | нет |
| 2 | хлопкопункт | 1/1 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 94.48%; 67.93%–100.00% | нет |
| 3 | замначальника | 1/1 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 93.97%; 65.86%–100.00% | нет |
| 4 | регулировщик | 1/1 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 93.45%; 63.79%–100.00% | нет |
| 5 | опустошение | 1/1 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 92.93%; 61.72%–100.00% | нет |
| 6 | аспирантура | 2/1 | 500 | 100.0% [99.2%; 100.0%] | 99.8% [98.9%; 100.0%] | 92.41%; 59.66%–90.00% | p_master 99.8%: max 90.0% |
| 7 | ультрафиолет | 2/1 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 91.90%; 57.59%–87.29% | p_master 100.0%: max 87.3% |
| 8 | аккумулятор | 2/1 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 91.38%; 55.52%–84.58% | p_master 100.0%: max 84.6% |
| 9 | переключатель | 2/1 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 90.86%; 53.45%–81.88% | p_master 100.0%: max 81.9% |
| 10 | госучреждение | 2/1 | 500 | 100.0% [99.2%; 100.0%] | 99.4% [98.3%; 99.8%] | 90.34%; 51.38%–79.17% | p_master 99.4%: max 79.2% |
| 11 | субординация | 3/2 | 500 | 100.0% [99.2%; 100.0%] | 98.8% [97.4%; 99.4%] | 89.83%; 49.31%–76.46% | p_master 98.8%: max 76.5% |
| 12 | эксплуатация | 3/2 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 89.31%; 47.24%–73.75% | p_master 100.0%: max 73.8% |
| 13 | объективность | 3/2 | 500 | 100.0% [99.2%; 100.0%] | 90.4% [87.5%; 92.7%] | 88.79%; 45.17%–71.04% | p_master 90.4%: max 71.0% |
| 14 | калейдоскоп | 3/2 | 500 | 100.0% [99.2%; 100.0%] | 100.0% [99.2%; 100.0%] | 88.28%; 43.10%–68.33% | p_master 100.0%: max 68.3% |
| 15 | фрагментация | 3/2 | 500 | 100.0% [99.2%; 100.0%] | 98.4% [96.9%; 99.2%] | 87.76%; 41.03%–65.62% | p_master 98.4%: max 65.6% |
| 16 | обязательство | 4/2 | 500 | 100.0% [99.2%; 100.0%] | 40.6% [36.4%; 45.0%] | 87.24%; 38.97%–62.92% | нет |
| 17 | прямоугольник | 4/2 | 500 | 100.0% [99.2%; 100.0%] | 73.2% [69.2%; 76.9%] | 86.72%; 36.90%–60.21% | p_master 73.2%: max 60.2% |
| 18 | дезинформация | 4/2 | 500 | 100.0% [99.2%; 100.0%] | 58.8% [54.4%; 63.0%] | 86.21%; 34.83%–57.50% | p_master 58.8%: max 57.5% |
| 19 | подхалимство | 4/2 | 500 | 100.0% [99.2%; 100.0%] | 82.4% [78.8%; 85.5%] | 85.69%; 32.76%–54.79% | p_master 82.4%: max 54.8% |
| 20 | благоразумие | 4/2 | 500 | 98.8% [97.4%; 99.4%] | 74.2% [70.2%; 77.8%] | 85.17%; 30.69%–52.08% | p_master 74.2%: max 52.1% |
| 21 | окаменелость | 5/3 | 500 | 99.2% [98.0%; 99.7%] | 56.6% [52.2%; 60.9%] | 84.66%; 28.62%–49.38% | p_master 56.6%: max 49.4% |
| 22 | дружелюбность | 5/3 | 500 | 88.4% [85.3%; 90.9%] | 50.8% [46.4%; 55.2%] | 84.14%; 26.55%–46.67% | p_master 50.8%: max 46.7% |
| 23 | разглядывание | 5/3 | 500 | 93.2% [90.6%; 95.1%] | 36.0% [31.9%; 40.3%] | 83.62%; 24.48%–43.96% | нет |
| 24 | подкаблучник | 5/3 | 500 | 96.6% [94.6%; 97.9%] | 42.0% [37.8%; 46.4%] | 83.10%; 22.41%–41.25% | p_master 42.0%: max 41.2% |
| 25 | селитроварня | 5/3 | 500 | 89.0% [86.0%; 91.5%] | 30.8% [26.9%; 35.0%] | 82.59%; 20.34%–38.54% | нет |
| 26 | медстраховка | 5/3 | 500 | 67.8% [63.6%; 71.7%] | 19.2% [16.0%; 22.9%] | 82.07%; 18.28%–35.83% | p_required 67.8%: min 82.1% |
| 27 | батрахотоксин | 5/3 | 500 | 96.2% [94.1%; 97.6%] | 70.0% [65.8%; 73.9%] | 81.55%; 16.21%–33.12% | p_master 70.0%: max 33.1% |
| 28 | проблематика | 5/3 | 500 | 74.0% [70.0%; 77.7%] | 6.0% [4.2%; 8.4%] | 81.03%; 14.14%–30.42% | p_required 74.0%: min 81.0%; p_master 6.0%: min 14.1% |
| 29 | кровопускание | 5/3 | 500 | 85.8% [82.5%; 88.6%] | 31.8% [27.9%; 36.0%] | 80.52%; 12.07%–27.71% | p_master 31.8%: max 27.7% |
| 30 | электромагнит | 5/4 | 500 | 5.4% [3.7%; 7.7%] | 0.6% [0.2%; 1.7%] | 80.00%; 10.00%–25.00% | p_required 5.4%: min 80.0%; p_master 0.6%: min 10.0% |

## Задания и независимо пересчитанные резервы

Резерв T4 измеряется до назначения. Параметры и цели приведены полностью; определения в отчёт не копируются.

| № | Тип / ID | Параметры | Резерв / минимум |
|---|---|---|---|
| 1 | T1 / l01-t1 | {"n": 6} | 31 / 18 |
| 2 | T1 / l02-t1 | {"n": 6} | 36 / 18 |
| 3 | T1 / l03-t1 | {"n": 7} | 34 / 21 |
| 4 | T1 / l04-t1 | {"n": 7} | 36 / 21 |
| 5 | T1 / l05-t1 | {"n": 8} | 27 / 24 |
| 6 | T1 / l06-t1 | {"n": 8} | 56 / 24 |
| 6 | T2 / l06-t2 | {"n": 3, "letter": "с"} | 16 / 9 |
| 7 | T1 / l07-t1 | {"n": 9} | 61 / 27 |
| 7 | T2 / l07-t2 | {"n": 3, "letter": "т"} | 16 / 9 |
| 8 | T1 / l08-t1 | {"n": 10} | 60 / 30 |
| 8 | T2 / l08-t2 | {"n": 3, "letter": "к"} | 25 / 9 |
| 9 | T1 / l09-t1 | {"n": 10} | 57 / 30 |
| 9 | T2 / l09-t2 | {"n": 3, "letter": "п"} | 17 / 9 |
| 10 | T1 / l10-t1 | {"n": 11} | 50 / 33 |
| 10 | T2 / l10-t2 | {"n": 3, "letter": "с"} | 9 / 9 |
| 11 | T1 / l11-t1 | {"n": 11} | 89 / 28 |
| 11 | T2 / l11-t2 | {"n": 3, "letter": "б"} | 18 / 8 |
| 11 | T3 / l11-t3 | {"minLength": 5, "hintTarget": "абсурд"} | 41 / 3 |
| 12 | T1 / l12-t1 | {"n": 12} | 87 / 30 |
| 12 | T2 / l12-t2 | {"n": 4, "letter": "п"} | 25 / 10 |
| 12 | T3 / l12-t3 | {"minLength": 5, "hintTarget": "акация"} | 53 / 3 |
| 13 | T1 / l13-t1 | {"n": 12} | 81 / 30 |
| 13 | T2 / l13-t2 | {"n": 4, "letter": "о"} | 17 / 10 |
| 13 | T3 / l13-t3 | {"minLength": 5, "hintTarget": "бекон"} | 47 / 3 |
| 14 | T1 / l14-t1 | {"n": 13} | 77 / 33 |
| 14 | T2 / l14-t2 | {"n": 4, "letter": "к"} | 23 / 10 |
| 14 | T3 / l14-t3 | {"minLength": 5, "hintTarget": "айпод"} | 43 / 3 |
| 15 | T1 / l15-t1 | {"n": 14} | 95 / 35 |
| 15 | T2 / l15-t2 | {"n": 4, "letter": "г"} | 19 / 10 |
| 15 | T3 / l15-t3 | {"minLength": 5, "hintTarget": "ангар"} | 54 / 3 |
| 16 | T1 / l16-t1 | {"n": 14} | 105 / 35 |
| 16 | T2 / l16-t2 | {"n": 4, "letter": "с"} | 29 / 10 |
| 16 | T3 / l16-t3 | {"minLength": 6, "hintTarget": "блевота"} | 13 / 3 |
| 16 | T4 / l16-t4 | {"targets": ["автобот"]} | 124 / 3 |
| 17 | T1 / l17-t1 | {"n": 15} | 123 / 38 |
| 17 | T2 / l17-t2 | {"n": 4, "letter": "к"} | 33 / 10 |
| 17 | T3 / l17-t3 | {"minLength": 6, "hintTarget": "гопник"} | 19 / 3 |
| 17 | T4 / l17-t4 | {"targets": ["гиря"]} | 149 / 3 |
| 18 | T1 / l18-t1 | {"n": 15} | 105 / 38 |
| 18 | T2 / l18-t2 | {"n": 5, "letter": "м"} | 19 / 13 |
| 18 | T3 / l18-t3 | {"minLength": 6, "hintTarget": "амнезия"} | 34 / 3 |
| 18 | T4 / l18-t4 | {"targets": ["админ"]} | 128 / 3 |
| 19 | T1 / l19-t1 | {"n": 16} | 116 / 40 |
| 19 | T2 / l19-t2 | {"n": 5, "letter": "п"} | 27 / 13 |
| 19 | T3 / l19-t3 | {"minLength": 6, "hintTarget": "восход"} | 28 / 3 |
| 19 | T4 / l19-t4 | {"targets": ["апостол"]} | 153 / 3 |
| 20 | T1 / l20-t1 | {"n": 16} | 109 / 40 |
| 20 | T2 / l20-t2 | {"n": 5, "letter": "г"} | 22 / 13 |
| 20 | T3 / l20-t3 | {"minLength": 7, "hintTarget": "берлога"} | 9 / 3 |
| 20 | T4 / l20-t4 | {"targets": ["алгебра"]} | 143 / 3 |
| 21 | T1 / l21-t1 | {"n": 17} | 150 / 34 |
| 21 | T2 / l21-t2 | {"n": 5, "letter": "с"} | 38 / 10 |
| 21 | T3 / l21-t3 | {"minLength": 7, "hintTarget": "емкость"} | 13 / 2 |
| 21 | T4 / l21-t4 | {"targets": ["акт", "аксель"]} | 173 / 4 |
| 21 | T5 / l21-t5 | {"minLength": 5, "ending": "ок"} | 4 / 2 |
| 22 | T1 / l22-t1 | {"n": 18} | 148 / 36 |
| 22 | T2 / l22-t2 | {"n": 5, "letter": "б"} | 28 / 10 |
| 22 | T3 / l22-t3 | {"minLength": 7, "hintTarget": "бледность"} | 10 / 2 |
| 22 | T4 / l22-t4 | {"targets": ["бедро", "бедность"]} | 171 / 4 |
| 22 | T5 / l22-t5 | {"minLength": 5, "ending": "ость"} | 5 / 2 |
| 23 | T1 / l23-t1 | {"n": 18} | 137 / 36 |
| 23 | T2 / l23-t2 | {"n": 5, "letter": "в"} | 22 / 10 |
| 23 | T3 / l23-t3 | {"minLength": 7, "hintTarget": "валяние"} | 16 / 2 |
| 23 | T4 / l23-t4 | {"targets": ["аврал", "авария"]} | 156 / 4 |
| 23 | T5 / l23-t5 | {"minLength": 5, "ending": "ина"} | 8 / 2 |
| 24 | T1 / l24-t1 | {"n": 19} | 129 / 38 |
| 24 | T2 / l24-t2 | {"n": 6, "letter": "к"} | 49 / 12 |
| 24 | T3 / l24-t3 | {"minLength": 7, "hintTarget": "булочник"} | 11 / 2 |
| 24 | T4 / l24-t4 | {"targets": ["аудио", "ауди"]} | 171 / 4 |
| 24 | T5 / l24-t5 | {"minLength": 6, "ending": "ик"} | 7 / 2 |
| 25 | T1 / l25-t1 | {"n": 19} | 154 / 38 |
| 25 | T2 / l25-t2 | {"n": 6, "letter": "с"} | 51 / 12 |
| 25 | T3 / l25-t3 | {"minLength": 8, "hintTarget": "инвестор"} | 4 / 2 |
| 25 | T4 / l25-t4 | {"targets": ["анис", "аист"]} | 178 / 4 |
| 25 | T5 / l25-t5 | {"minLength": 6, "ending": "ия"} | 3 / 2 |
| 26 | T1 / l26-t1 | {"n": 20} | 232 / 40 |
| 26 | T2 / l26-t2 | {"n": 6, "letter": "к"} | 37 / 12 |
| 26 | T3 / l26-t3 | {"minLength": 8, "hintTarget": "доставка"} | 3 / 2 |
| 26 | T4 / l26-t4 | {"targets": ["адрес", "адвокат", "авто"]} | 269 / 6 |
| 26 | T6 / l26-t6 | {"n": 3, "category": "actions", "words_count": 16, "words_sha256": "e7564f52d415e96c94af6948fe3bda8f4a272044176e8bfaa325f1f2058f9659"} | 13 / 6 |
| 27 | T1 / l27-t1 | {"n": 20} | 244 / 40 |
| 27 | T2 / l27-t2 | {"n": 6, "letter": "с"} | 49 / 12 |
| 27 | T3 / l27-t3 | {"minLength": 8, "hintTarget": "анархист"} | 17 / 2 |
| 27 | T5 / l27-t5 | {"minLength": 6, "ending": "ист"} | 6 / 2 |
| 27 | T7 / l27-t7 | {"n": 3, "letter": "х"} | 34 / 6 |
| 28 | T1 / l28-t1 | {"n": 21} | 245 / 42 |
| 28 | T2 / l28-t2 | {"n": 6, "letter": "п"} | 63 / 12 |
| 28 | T4 / l28-t4 | {"targets": ["ака", "аборт", "аба"]} | 288 / 6 |
| 28 | T5 / l28-t5 | {"minLength": 7, "ending": "ка"} | 8 / 2 |
| 28 | T6 / l28-t6 | {"n": 4, "category": "animals", "words_count": 12, "words_sha256": "1bcdba09b27b7ede8e3481656e000d402dd20afe4f64b6f72beef16bbb139a2c"} | 9 / 8 |
| 29 | T1 / l29-t1 | {"n": 22} | 224 / 44 |
| 29 | T3 / l29-t3 | {"minLength": 8, "hintTarget": "вероника"} | 12 / 2 |
| 29 | T4 / l29-t4 | {"targets": ["анус", "акр", "анкер"]} | 252 / 6 |
| 29 | T5 / l29-t5 | {"minLength": 7, "ending": "ание"} | 5 / 2 |
| 29 | T7 / l29-t7 | {"n": 5, "letter": "у"} | 58 / 10 |
| 30 | T2 / l30-t2 | {"n": 7, "letter": "к"} | 53 / 14 |
| 30 | T3 / l30-t3 | {"minLength": 9, "hintTarget": "кинотеатр"} | 2 / 2 |
| 30 | T4 / l30-t4 | {"targets": ["аконит", "аким", "агент"]} | 294 / 6 |
| 30 | T5 / l30-t5 | {"minLength": 8, "ending": "ние"} | 2 / 2 |
| 30 | T6 / l30-t6 | {"n": 5, "category": "plants", "words_count": 11, "words_sha256": "63f31d1442d3d0b9a09e0f47dcf0bb19b4e35068fb428891a06c44dc1aabf686"} | 10 / 10 |

## Перебалансировка

| Кандидат | Изменение | GEN-04 | Сумма отклонений | Уровни вне целей |
|---|---|---|---|---|
| 0 | {"kind": "initial"} | False | 4.592840 | [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 24, 26, 27, 28, 29, 30] |
| 1 | {"kind": "assignment", "level": 30, "task": "l30-t2"} | False | 4.584840 | [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 24, 26, 27, 28, 29, 30] |
| 2 | {"kind": "source_slot", "level": 30, "old": "подталкивание", "new": "электромагнит"} | False | 4.392081 | [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 24, 26, 27, 28, 29, 30] |
| 3 | {"kind": "assignment", "level": 30, "task": "l30-t2"} | False | 4.402081 | [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 24, 26, 27, 28, 29, 30] |
| 4 | {"kind": "source_slot", "level": 30, "old": "электромагнит", "new": "бодрствование"} | False | 4.641423 | [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 24, 25, 26, 27, 28, 29, 30] |
| 5 | {"kind": "assignment", "level": 30, "task": "l30-t2"} | False | 4.402081 | [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 24, 26, 27, 28, 29, 30] |
| 6 | {"kind": "source_slot", "level": 30, "old": "электромагнит", "new": "приостановка"} | False | 4.396081 | [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 24, 26, 27, 28, 29, 30] |

## Отказ GEN-04

- balance_deviations: уровень None, задание None, jaccard: Mean=0.03052955 (max .03), maximum=0.19361277 (max .15)
- parity_deviations: уровень 24, задание l24-t4, jvm_hint: DefinitionHint/Python text or suitability differs: баки
- parity_deviations: уровень 28, задание l28-t4, jvm_hint: DefinitionHint/Python text or suitability differs: баки
- parity_deviations: уровень 28, задание l28-t4, jvm_hint: DefinitionHint/Python text or suitability differs: батек
- parity_deviations: уровень 23, задание l23-t4, jvm_hint: DefinitionHint/Python text or suitability differs: злыдня
- parity_deviations: уровень 29, задание l29-t4, jvm_hint: DefinitionHint/Python text or suitability differs: кека
- parity_deviations: уровень 16, задание l16-t4, jvm_hint: DefinitionHint/Python text or suitability differs: лета
- parity_deviations: уровень 21, задание l21-t4, jvm_hint: DefinitionHint/Python text or suitability differs: лета
- parity_deviations: уровень 25, задание l25-t4, jvm_hint: DefinitionHint/Python text or suitability differs: лета
- parity_deviations: уровень 28, задание l28-t4, jvm_hint: DefinitionHint/Python text or suitability differs: лета
- parity_deviations: уровень 30, задание l30-t4, jvm_hint: DefinitionHint/Python text or suitability differs: лета
- parity_deviations: уровень 17, задание l17-t4, jvm_hint: DefinitionHint/Python text or suitability differs: полом
- parity_deviations: уровень 19, задание l19-t4, jvm_hint: DefinitionHint/Python text or suitability differs: полом
- parity_deviations: уровень 17, задание l17-t4, jvm_hint: DefinitionHint/Python text or suitability differs: прог
- parity_deviations: уровень 19, задание l19-t4, jvm_hint: DefinitionHint/Python text or suitability differs: птах
- parity_deviations: уровень 20, задание l20-t4, jvm_hint: DefinitionHint/Python text or suitability differs: раба
- parity_deviations: уровень 28, задание l28-t4, jvm_hint: DefinitionHint/Python text or suitability differs: раба
- parity_deviations: уровень 26, задание l26-t4, jvm_hint: DefinitionHint/Python text or suitability differs: сверка
- parity_deviations: уровень 29, задание l29-t4, jvm_hint: DefinitionHint/Python text or suitability differs: сверка
- parity_deviations: уровень 25, задание l25-t4, jvm_hint: DefinitionHint/Python text or suitability differs: сир
- parity_deviations: уровень 29, задание l29-t4, jvm_hint: DefinitionHint/Python text or suitability differs: сир
- parity_deviations: уровень 21, задание l21-t4, jvm_hint: DefinitionHint/Python text or suitability differs: сонь
- parity_deviations: уровень 22, задание l22-t4, jvm_hint: DefinitionHint/Python text or suitability differs: сонь
- parity_deviations: уровень 25, задание l25-t4, jvm_hint: DefinitionHint/Python text or suitability differs: тин
- parity_deviations: уровень 30, задание l30-t4, jvm_hint: DefinitionHint/Python text or suitability differs: тин
- parity_deviations: уровень 25, задание l25-t4, jvm_hint: DefinitionHint/Python text or suitability differs: тора
- parity_deviations: уровень 26, задание l26-t4, jvm_hint: DefinitionHint/Python text or suitability differs: тора
- parity_deviations: уровень 28, задание l28-t4, jvm_hint: DefinitionHint/Python text or suitability differs: тора
- parity_deviations: уровень 30, задание l30-t4, jvm_hint: DefinitionHint/Python text or suitability differs: тора
- parity_deviations: уровень 30, задание l30-t4, jvm_hint: DefinitionHint/Python text or suitability differs: торги
- parity_deviations: уровень 19, задание l19-t4, jvm_hint: DefinitionHint/Python text or suitability differs: хав
- parity_deviations: уровень 26, задание l26-t4, jvm_hint: DefinitionHint/Python text or suitability differs: хав

При исчерпании бюджета модель знания, целевые вероятности и проверки не меняются. Кандидат не установлен в data/; release и аудит прав этим отчётом не разрешаются.
