# PRE04-12: исправление G10

## ИТОГ

Обновлён `ComponentInteractionTest.drawerKeepsSupportedDestinationsAndInvokesNavigation`:

- в строгий набор маршрутов добавлен актуальный маршрут `time`;
- добавлена пользовательская навигация по пункту «Время игры» с проверкой `Destination.Time`;
- проверка маршрутов по-прежнему сравнивает exact set, без ослабления критерия.

Экран времени не удалялся. `Destination.Time` уже отображается в `WordsDrawer`, а
`WordsNavHost` уже связывает маршрут `time` с `TimeRoute`.

## ПРОВЕРЕНО

- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` — PASS.

Полный Android-инструментальный запуск не выполнялся: для него нужен эмулятор или
подключённое устройство; полный gate запускает оркестратор.

## НЕ ПРОВЕРЕНО / ОТКЛОНЕНИЯ

Runtime-прохождение на устройстве не проверено. Отклонений от задания нет.
