# Макеты экрана «Проекты и код»

Два макета основного состояния экрана в формате SLM/CNL (`*.layout.md`) для Paper Editor
и головная страница с пояснениями. Кадр один и тот же: macOS, окно 1440×900, проект
выбран, у проекта несколько кодинг-сессий, справа журнал активной сессии.

| Файл | Назначение |
| --- | --- |
| [coding-projects-current.layout.md](coding-projects-current.layout.md) | Текущая реализация: `UnifiedSidebar` + `CodingScreen(showProjectsPanel = false)` + `CodingChat` |
| [coding-projects-proposed.layout.md](coding-projects-proposed.layout.md) | Предложенная композиция того же состояния; отличия перечислены ниже |

## Источник правды для макета «как есть»

| Область макета | Код |
| --- | --- |
| Оболочка, тулбар 28 dp, инсет «светофора» 78 dp | `app/src/commonMain/kotlin/io/aequicor/magicpaper/App.kt` (`TopBar`), `desktopApp/src/main/kotlin/io/aequicor/magicpaper/main.kt` (`MacTitleBarHeight`, `MacTrafficLightsWidth`) |
| Боковая панель 272 dp + ручка 8 dp | `designSystem/.../PaperResizablePanels.kt` (`preferredWidth = 272f`), `app/.../ui/screens/UnifiedSidebar.kt` |
| Строки проектов/сессий, кружки статусов, футер «Новый чат / Новый проект» | `app/.../ui/screens/UnifiedSidebar.kt` (`ProjectSectionHeader`, `UnifiedSessionRow`), `feature/coding/api/.../ui/screens/CodingStatusPresentation.kt` (`ActivityDot`, тона и подписи статусов) |
| Заголовок сессии «имя / проект / режим» | `feature/coding/impl/.../ui/screens/CodingScreen.kt` (`CodingChat`, `PaperWorkspaceHeading`) |
| Пузыри журнала, свёрнутые строки инструментов | `CodingScreen.kt` (`CodingMessageBubble`, `ToolStepContent`, `paperConversationMessage`), цвета `docs/desktop-ui/BRANDBOOK.md` |
| Композер: градиент, плейсхолдер, «+», режим, контекст, чип модели, «Отправить» | `CodingScreen.kt` (`CodingComposer`), `designSystem/.../PaperWorkspace.kt` (`PaperWorkspaceComposer`) |

Палитра и типографика взяты из `designSystem/.../PaperTheme.kt` и `docs/desktop-ui/BRANDBOOK.md`:
canvas `#F3EBDD`, surface `#FFFDFA`, selected `#DDD2EB` (в строке — 55% по canvas ≈ `#E7DDE5`),
текст `#35242D`, вторичный `#625C70`, действие `#62558D`, пузыри агента/пользователя
`#DDD8E0`/`#D8C9E7`, поверхность шага `#FFFDFA` 55% ≈ `#FAF5ED`, композер
`#F0E8DE → #E5DDD1`, кружки статусов `#E89B99`/`#E8C66C`/`#8FC7A2`/`#E5E2E3`.
Размеры текста: chrome 13, body 14, label 13, title 18.

## Отличия предложенной композиции

1. **Строка статуса сессии над журналом** (`status_strip_*`): кружок статуса, подпись
   («ждёт запроса»), проект и режим, время обновления. Сейчас состояние живёт только в
   кружках боковой панели и во временных `PaperStatus`/`Notice`, поэтому после навигации
   причину простоя не видно без возврата в список.
2. **Проект как строка первого уровня**: сводный кружок статуса проекта и счётчик сессий
   видны всегда, а не только через `aggregateCodingStatus` в тултипе; действие «новая
   сессия» (`+`) видно на выбранном проекте, а не появляется только при наведении
   (`PaperHoverActions` в `UnifiedSidebar`/`CodingScreen`).
3. **Ширина панели 288 dp вместо 272**: имена проектов и сессий помещаются без обрезки
   (`PaperFadingText` сейчас гасит хвосты имён).
4. **Группировка шагов прогона**: одна свёрнутая поверхность «Ход работы · N действий»
   вместо ряда отдельных карточек `ToolStepContent`; ответ агента получает больший вес
   (ширина 820 вместо 760) и остаётся главным объектом журнала.
5. **Режим сессии виден в композере** сегментированным переключателем
   («Обычный / Исследование / Планирование») вместо пункта в меню «+»
   (`CodingComposer`); меню остаётся только для вложений.
6. **Подзаголовок журнала** освобождён от дубля «проект / режим» (его несёт строка
   статуса) и говорит о содержимом области: «Журнал сессии».

Предложение не меняет навигацию, DI и владельцев состояния: это правки композиции
существующих поверхностей Paper, а не новая инфраструктура.

## Проверка и открытие

Головная проверка обоих документов (компиляция SLM, валидация документа, проверка
props/variants по реестру Paper, настоящий Compose-рендер в PNG):

```sh
E=tools/paper-editor/build/compose/binaries/main/app/PaperEditor.app/Contents/MacOS/PaperEditor
$E --agent render /tmp/response.json docs/desktop-ui/layouts/coding-projects-current.layout.md /tmp/current.png
$E --agent render /tmp/response.json docs/desktop-ui/layouts/coding-projects-proposed.layout.md /tmp/proposed.png
```

Последний прогон: оба ответа `{"valid":true,"width":1440,"height":900,"diagnostics":""}`.
Исполняемый файл взят из уже собранного `:tools:paper-editor:createDistributable`
(`tools/paper-editor/build/compose/binaries/main/app`); пересобрать можно командой
`./gradlew -PpaperEditor=true :tools:paper-editor:createDistributable`.
В самом редакторе макеты открываются через Open project по папке `docs/desktop-ui/layouts`.

## Ограничения макетов

- Рендерер SLM не загружает шрифтовые семейства по имени, поэтому подписи на макетах
  набраны резервным гротеском; в приложении те же роли используют Literata/Cormorant
  (`PaperFonts`). Кегли, начертания и цвета соответствуют токенам Paper.
- Иконки тулбара, кружки статусов, «+»/«📎» и кольцо контекста нарисованы примитивами как
  схематичные глифы реальных компонентов (`PaperIconButton`, `PaperActivityIndicator`,
  `PaperContextIndicator`); «светофор» macOS принадлежит ОС и на макете не рисуется.
- Из компонентов Paper экземпляром вставлена только кнопка «Отправить»
  (`PaperButton`, kind PRIMARY): остальные фикстуры каталога несут фиксированный
  демонстрационный контент и белую подложку предпросмотра, которая исказила бы поверхности.
- Покрыто только основное состояние (выбранный проект, журнал, композер). Пустые
  состояния, прогон/блокировка, узкое окно и диалоги в этих файлах не моделировались.
- Проверены компиляция, валидация и статичный рендер на macOS-метриках. Windows/Linux,
  масштаб текста 150/200% и интерактивность не проверялись: это макет, а не реализация.
