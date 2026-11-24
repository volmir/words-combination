# Индекс документов

Единственная действующая спецификация игры — [SPEC.md](SPEC.md). Исходный
контракт v2 и отчёты прогонов в [CAMPAIGN.md](CAMPAIGN.md) — история.
Текущий снимок — [PROJECT_STATUS.md](PROJECT_STATUS.md). Пути файлов не
менялись: документы владельца и исторические доказательства сохранены на месте.

Статусы: **действует** — используется сейчас; **история** — архив завершённой
задачи или период до текущей сборки; **юридический** — разрешение, аудит или
уведомление правового характера (действует, пока не заменено владельцем).
Колонка «заменён чем» указывает текущий документ для исторических материалов.

| Документ | Статус | Заменён чем |
|---|---|---|
| `SPEC.md` | действует | — |
| `INDEX.md` | действует | — |
| `PROJECT_STATUS.md` | действует | — |
| `RELEASE_CHECKLIST_1_1.md` | действует (ручная приёмка релиза 1.1.0 на устройстве) | — |
| `GOLD_03A_REPORT.md` | история (удаление контрольного списка GOLD-03A) | `SPEC.md` |
| `DATA_03_REPORT.md` | история (чистка словаря DATA-03) | `SPEC.md` |
| `DICT_03B_REPORT.md`, `DICT_03B_REPORT.json` | история (шесть самостоятельных слов DICT-03B) | `SPEC.md` |
| `UX_12_REPORT.md` | история (компактная панель подсказки) | `SPEC.md` §4 |
| `SAVE_16_REPORT.md` | действует (правила переноса прогресса) | — |
| `CLEAN_21_REPORT.md` | действует (основания удаления кода и сохранения аудитов) | — |
| `FAM_14_REPORT.md` | действует (генерация данных узнаваемости FAM-14) | — |
| `DIMINUTIVE_CANDIDATES.md`, `DIMINUTIVE_CANDIDATES.json` | действует (кандидаты, к словарю не применяются) | — |
| `CAMPAIGN.md` | история | `SPEC.md` (заметка в начале файла) |
| `OWN_HINTS_TOOLCHAIN.md` | действует | — |
| `RELEASE_SIGNING.md` | действует | — |
| `FDROID_REPRO_02.md` | действует | — |
| `KNOWN_WORDS.md` | действует | — |
| `CAMPAIGN_VALIDATION.md` | действует (gate GEN-04) | — |
| `PLAY_TIME_QA.md` | история (учёт времени удалён) | `SPEC.md` §6 |
| `QA_CHECKLIST.md` | действует | — |
| `PRIVACY_POLICY.md` | юридический | — |
| `PRIVACY_PUBLICATION_AUDIT.md` | юридический | — |
| `PRIVACY_GIT_PUBLICATION.md` | юридический | — |
| `LEGAL_RELEASE_AUDIT.md` | юридический | — |
| `GRAPHICS_PROVENANCE_AUDIT.md` | юридический | — |
| `LEGAL_GRAPHICS_02_REPORT.md` | юридический | — |
| `VOCABULARY_AUDIT.md`, `VOCABULARY_AUDIT.json` | юридический | — |
| `owned_hints.json` | юридический (39 текстов CC0) | — |
| `OWN_HINTS_OWNER_APPROVAL.json` | юридический | — |
| `OWN_HINTS_MANUAL_AUDIT.json` | юридический (аудит 39/39) | — |
| `RASTER_GRAPHICS_OWNER_APPROVAL.json` | юридический | — |
| `OWN_HINTS_OWNER_REVIEW.md` | юридический (RIGHTS-01) | — |
| `OWN_HINTS_REVIEW.md` | юридический (LCH-02) | — |
| `LEX11_01_OWNER_REVIEW.md`, `LEX13_01_OWNER_REVIEW.md` | юридический | — |
| `ANDROID_UI_HANG_DIAGNOSIS.md` | история | `SPEC.md` |
| `ARCH_01_REPORT.md` | история | `SPEC.md` §5 |
| `CAMPAIGN_GENERATION.md`, `CAMPAIGN_LOADING.md` | история | `SPEC.md` §§1–5 |
| `CAMPAIGN_REPORT.md`, `CAMPAIGN_REVIEW.md`, `CAMPAIGN_SIMULATION.md` | история | `SPEC.md` |
| `CORE_CAMPAIGN.md` | история | `SPEC.md` §4 |
| `SELECTIVE_CAMPAIGN.md` | история | `SPEC.md` |
| `DATA_PIPELINE_V2.md` | история | `SPEC.md` §7 |
| `DATASTORE_JSON_MIGRATION.md` | история | `SPEC.md` §5 |
| `SQLITE_REMOVAL.md`, `SQLITE_REPRODUCIBILITY_AUDIT.md` | история | `SPEC.md` §5 |
| `DEFINITIONS_POLICY.md`, `LOCAL_DEFINITIONS.md` | история | `SPEC.md` §8 |
| `DICTIONARY_CLEANUP.md`, `WORD_SUITABILITY.md`, `CATEGORIES.md` | история | `SPEC.md` §8 |
| `LEVEL_PROFILES.md`, `LEVEL_SELECTION.md`, `FINISHING_CAMPAIGN.md` | история | `SPEC.md` §1 |
| `LEVEL_HINTS.md`, `LEVEL_ROUTE.md`, `LEVEL_CARD_COLORS.md` | история | `SPEC.md` §§1, 4 |
| `OWN_HINTS_LICENSE_RELEASE_AUDIT.md` | история | `LEGAL_RELEASE_AUDIT.md` |
| `OWN_HINTS_MANUAL_AUDIT.md` | история | `OWN_HINTS_MANUAL_AUDIT.json` |
| `OWN_HINTS_SCOPE.md`, `OWN_HINTS_VERIFICATION.md` | история | `SPEC.md` §8 |
| `RIGHTS02_REPORT.md`, `RIGHTS03_REPORT.md`, `RIGHTS_RELEASE_AUDIT.md` | история | `LEGAL_RELEASE_AUDIT.md` |
| `RELEASE_RIGHTS_DECISION.md`, `RELEASE_APPLICATION_ID.md` | история | ожидает решения владельца |
| `RELEASE_SIGNING_REPORT.md` | история | `RELEASE_SIGNING.md` |
| `FDROID_01_REPORT.md`, `FDROID_REPRO_01.md`, `FDROID_SUBMISSION_01.md` | история | `FDROID_REPRO_02.md` |
| `SHORT15_05_REPORT.md`, `SHORT15_INSTALL.md`, `SHORT15_RELEASE_GATE.md`, `SHORT15_USABILITY_RELEASE_GATE.md` | история | `SPEC.md`, `PROJECT_STATUS.md` |
| `CUR03_ACCEPTANCE.md`, `CRITICAL_UX_SCREEN_QA.md`, `PRE_RELEASE_ANDROID_QA.md` | история | `QA_CHECKLIST.md` |
| `LCH01_REPORT.md`, `LCH02_REPORT.md`, `LCH03_REPORT.md` | история | `OWN_HINTS_TOOLCHAIN.md` |
| `TIME01_REPORT.md` … `TIME04_REPORT.md` | история | `SPEC.md` §6 |
| `UXR01_REPORT.md` … `UXR04_REPORT.md` | история | `SPEC.md`, `QA_CHECKLIST.md` |
| `PRE04_DRAFT_DECISION.md`, `PRE04_FAILURE_TRIAGE.md`, `PRE04_NATIVE_TEST_MATRIX.md` | история | `QA_CHECKLIST.md` |
| `PRE04_FIX_G01_HEADERS.md`, `PRE04_FIX_G01_LETTERS.md`, `PRE04_FIX_G01_NATIVE.md`, `PRE04_FIX_G02.md` … `PRE04_FIX_G15.md` | история | `QA_CHECKLIST.md` |
| `OWN_HINTS_OWNER_APPROVAL.template.json` | история | `OWN_HINTS_OWNER_APPROVAL.json` |
| `decisions/2026-11-06-clean-game-vocabulary.md` | история | `SPEC.md` §8 |
| `decisions/2026-11-06-provisional-definitions.md` | история | `SPEC.md` §8 |
| `fdroiddata/FDROID-01-remove-updatecheckdata.patch`, `fdroiddata/metadata/io.github.volmir.wordscombination.yml` | история | заявка F-Droid !58000 |
| `uiux/SHORT15_BASELINE.md`, `uiux/SHORT15_FINAL_REPORT.md`, `uiux/SHORT15_MANUAL_QA.md`, `uiux/SHORT15_REGRESSION.md` | история | `SPEC.md`, `QA_CHECKLIST.md` |
| `qa/pre04-17/*` (артефакты прогона) | история | — (архив) |
| `snapshots/data01/*` (снимки решений) | история | — (архив) |
| `audits/` (инвентарь и происхождение, JSON) | юридический | — |
