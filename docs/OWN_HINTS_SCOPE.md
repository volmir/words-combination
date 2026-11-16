# LCH-02 — реестр целей

Источник истины — установленный `data/campaign.json`, не curation_snapshot.json.

SHORT15-05 и CUR-03 завершены; SHA установленного пакета совпадают с CUR03_ACCEPTANCE.md. Свежая verify_installed проверяет связи manifest и файлов; production-тесты проверяют действующую кампанию.

39 уникальных целей / 40 назначений: 9 T3, 11 T4, 20 T6. «Артист» объединяет T4/T6. Ориентир 25/11 не использован как квота: HintRules.selectTarget допускает любую из 20 профессий, поэтому каждая включена один раз. T6 n=4 не ограничивает список допустимых слов и покрытие текстами. T1/T2/T5/T7 — открытые задания без назначенного смыслового ответа; их словарь сюда не включён.

Набор — кандидат вне data/, он не подключён к DefinitionsRepository и не устанавливает кампанию. Действующая БД и поведение HintLadder остаются прежними.

## Проверенные входы

| Файл | SHA-256 |
|---|---|
| `campaign.json` | `1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e` |
| `words.txt` | `9c1aed9e0011e4173dffd0f4281832d821d4de90623d19273f7fc8e68c212489` |
| `manifest.json` | `881bff5a44a234169326105d25226fd878f9b5e01028d3094f38481fc4ab1213` |
| `definitions.db` | `406d127a444b02d9eaddb30e820dd7cadc200b967338554a552f81468ee6c097` |

## Полный перечень

| Слово | Нормализовано | Уровень / задание | Тип | T4 обязательна | Источник выбора | В WordIndex |
|---|---|---|---|---|---|---|
| абсурд | абсурд | 6 / l06-t3 | T3 | нет | campaign.hintTarget | да |
| адресок | адресок | 11 / l11-t3 | T3 | нет | campaign.hintTarget | да |
| акробат | акробат | 14 / l14-t6 | T6 | нет | campaign.words | да |
| актриса | актриса | 14 / l14-t6 | T6 | нет | campaign.words | да |
| акционер | акционер | 10 / l10-t3 | T3 | нет | campaign.hintTarget | да |
| антитело | антитело | 15 / l15-t3 | T3 | нет | campaign.hintTarget | да |
| артист | артист | 14 / l14-t4, 14 / l14-t6 | T4, T6 | да | campaign.targets, campaign.words | да |
| артистка | артистка | 14 / l14-t6 | T6 | нет | campaign.words | да |
| астрал | астрал | 7 / l07-t3 | T3 | нет | campaign.hintTarget | да |
| астролог | астролог | 9 / l09-t3 | T3 | нет | campaign.hintTarget | да |
| банк | банк | 14 / l14-t4 | T4 | да | campaign.targets | да |
| банкир | банкир | 14 / l14-t6 | T6 | нет | campaign.words | да |
| бариста | бариста | 14 / l14-t6 | T6 | нет | campaign.words | да |
| батрак | батрак | 14 / l14-t6 | T6 | нет | campaign.words | да |
| билет | билет | 12 / l12-t4 | T4 | да | campaign.targets | да |
| бонна | бонна | 14 / l14-t6 | T6 | нет | campaign.words | да |
| бортник | бортник | 14 / l14-t6 | T6 | нет | campaign.words | да |
| ботаник | ботаник | 14 / l14-t6 | T6 | нет | campaign.words | да |
| верзила | верзила | 8 / l08-t3 | T3 | нет | campaign.hintTarget | да |
| вероника | вероника | 13 / l13-t3 | T3 | нет | campaign.hintTarget | да |
| весна | весна | 13 / l13-t4 | T4 | да | campaign.targets | да |
| дантист | дантист | 14 / l14-t6 | T6 | нет | campaign.words | да |
| десерт | десерт | 11 / l11-t4 | T4 | да | campaign.targets | да |
| диакон | диакон | 14 / l14-t6 | T6 | нет | campaign.words | да |
| диктор | диктор | 14 / l14-t6 | T6 | нет | campaign.words | да |
| канонир | канонир | 14 / l14-t6 | T6 | нет | campaign.words | да |
| кантор | кантор | 14 / l14-t6 | T6 | нет | campaign.words | да |
| каратист | каратист | 14 / l14-t6 | T6 | нет | campaign.words | да |
| километр | километр | 12 / l12-t3 | T3 | нет | campaign.hintTarget | да |
| ковер | ковер | 13 / l13-t4 | T4 | да | campaign.targets | да |
| отель | отель | 11 / l11-t4 | T4 | да | campaign.targets | да |
| проблема | проблема | 12 / l12-t4 | T4 | да | campaign.targets | да |
| работа | работа | 9 / l09-t4 | T4 | да | campaign.targets | да |
| радист | радист | 14 / l14-t6 | T6 | нет | campaign.words | да |
| санитар | санитар | 14 / l14-t6 | T6 | нет | campaign.words | да |
| стадион | стадион | 14 / l14-t4 | T4 | да | campaign.targets | да |
| таксатор | таксатор | 14 / l14-t6 | T6 | нет | campaign.words | да |
| танкист | танкист | 14 / l14-t6 | T6 | нет | campaign.words | да |
| утро | утро | 10 / l10-t4 | T4 | да | campaign.targets | да |

## Полная категория T6

Уровень 14, `l14-t6`, `professions`, n=4. Таблица независима от числа подсказок. Все слова составимы из «контрабандист», включены в индекс и проверены по word_metadata. Категория — принятое владельцем множество занятий; оно включает исторические занятия, духовные саны и спортивные специализации.

| Слово | Категория | Проверяемый источник | Есть текст |
|---|---|---|---|
| акробат | professions | owner:SHORT15-05:2026-11-16 | да |
| актриса | professions | owner:SHORT15-05:2026-11-16 | да |
| артист | professions | owner:SHORT15-05:2026-11-16 | да |
| артистка | professions | owner:SHORT15-05:2026-11-16 | да |
| банкир | professions | owner:SHORT15-05:2026-11-16 | да |
| бариста | professions | owner:SHORT15-05:2026-11-16 | да |
| батрак | professions | owner:SHORT15-05:2026-11-16 | да |
| бонна | professions | owner:SHORT15-05:2026-11-16 | да |
| бортник | professions | owner:SHORT15-05:2026-11-16 | да |
| ботаник | professions | owner:SHORT15-05:2026-11-16 | да |
| дантист | professions | owner:SHORT15-05:2026-11-16 | да |
| диакон | professions | owner:SHORT15-05:2026-11-16 | да |
| диктор | professions | owner:SHORT15-05:2026-11-16 | да |
| канонир | professions | owner:SHORT15-05:2026-11-16 | да |
| кантор | professions | owner:SHORT15-05:2026-11-16 | да |
| каратист | professions | owner:SHORT15-05:2026-11-16 | да |
| радист | professions | owner:SHORT15-05:2026-11-16 | да |
| санитар | professions | owner:SHORT15-05:2026-11-16 | да |
| таксатор | professions | owner:SHORT15-05:2026-11-16 | да |
| танкист | professions | owner:SHORT15-05:2026-11-16 | да |
