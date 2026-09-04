# План: типографика «Магической бумаги» — фирменный шрифт с поддержкой i18n

Цель: заменить системные `FontFamily.Serif`/sans на **собственный шрифтовой стек**, который
(1) соответствует стилю программы (пергамент, чернила, книжная магия),
(2) одинаков на всех таргетах (jvm / js+wasmJs / android),
(3) покрывает основные письменности для i18n (латиница+расширения, кириллица+расширения,
греческий, вьетнамский) и корректно деградирует на остальные (китайский, арабский…).

Ограничения проекта: KMP (Compose Multiplatform 1.11.1), без материальных иконок,
пастельный минимализм, лицензионная чистота (встраивание шрифтов в дистрибутивы).

---

## 1. Текущее состояние (точка старта)

| Что | Где | Как сейчас | Проблема |
|---|---|---|---|
| Заголовки | `ui/theme/MagicTheme.kt` | `FontFamily.Serif` (системный) | Разный вид на каждой ОС: на Android один сериф, на Linux другой, на веб — третий |
| Текст | там же | дефолт Material (системный sans) | Не «книжный», выбивается из стиля пергамента |
| Код в чате | `ui/components/ChatMarkdown.kt` | `markdownTypography()` по умолчанию → `FontFamily.Monospace` (системный) | На разных платформах разный моно; на веб может не совпадать по метрикам |
| Ресурсы | `composeResources/` | только `drawable/` | Нет `font/`, нет строк — все 278 литералов в коде (24 файла) захардкожены по-русски |

Вывод: нужен **встроенный шрифтовой стек через Compose Resources** (`composeResources/font/`),
работающий на всех таргетах из одного общего sourceSet, плюс фундамент для будущей локализации.

---

## 2. Выбор гарнитур (решение)

Стиль программы — «книга заклинаний на пергаменте». Стек из трёх ролей:

| Роль | Гарнитура | Лицензия | Почему |
|---|---|---|---|
| **Дисплейная** (бренд, крупные заголовки) | **Cormorant Garamond** (Christian Thalmann) | OFL 1.1 | Каллиграфический гарамон с «магическим» характером; официальная кириллица (правка Алексея Чекуляева), подмножества `cyrillic, cyrillic-ext, latin, latin-ext, vietnamese` |
| **Текстовая** (чат, доки, UI-текст) | **Literata** (TypeTogether, шрифт Google Play Books) | OFL 1.1 | Создана для долгого чтения с экрана; латиница+расш., кириллица+расш., греческий (политонный), вьетнамский; призёр «Modern Cyrillic 2021»; веса 200–900 |
| **Моно** (код, инлайн-код, технические поля) | **JetBrains Mono** | OFL 1.1 | Кириллица, отличная различимость, лигатуры; стандарт для кода |

**Почему не одна гарнитура на всё:** чистый сериф в мелких подписях/кнопках утяжеляет
«простой UI». Cormorant — display-only (тонкие штрихи, читается от ~17sp), поэтому ему
отведены только крупные роли; всю мелкую работу несёт Literata.

**Почему не системные стеки:** суть обновления — идентичность и предсказуемость: один вид
на десктопе, Android и в браузере. Системный `Serif` не даёт ни того, ни другого.

**i18n-покрытие стека:** латиница (вкл. расширенную и вьетнамскую), кириллица (вкл.
расширения для украинских/белорусских/болгарских/языков СНГ), греческий, вьетнамский —
это русская, английская и большинство европейских локалей «из коробки».
Письменности вне покрытия (CJK, арабская, иврит, деванагари…) **не встраиваем**
(это сотни мегабайт Noto): Compose/Skia автоматически подтягивает системный
фолбэк-шрифт платформы для недостающих глифов — поведение штатное на всех таргетах,
включая веб (браузерный фолбэк).

---

## 3. Целевая модель кода

### 3.1 Ресурсы — `shared/src/commonMain/composeResources/font/`

```
font/
  literata_regular.ttf        # 400 — текст чата, доки, настройки
  literata_italic.ttf         # 400 курсив — цитаты в markdown
  literata_medium.ttf         # 500 — подзаголовки, акценты
  literata_semibold.ttf       # 600 — жирные акценты, кнопки-акценты
  cormorant_garamond_medium.ttf     # 500 — titleMedium
  cormorant_garamond_semibold.ttf   # 600 — titleLarge, бренд в шапке
  cormorant_garamond_bold.ttf       # 700 — Welcome/крупные дисплейные роли
  jetbrains_mono_regular.ttf        # 400 — блоки кода
  jetbrains_mono_medium.ttf         # 500 — инлайн-код/акценты в коде
  OFL.txt                           # тексты лицензий (обязательно по OFL)
```

Объём до оптимизации ≈ 2.8 МБ (9 файлов по ~300 КБ) — приемлемо для APK/десктопа.

Источники (проверено, все статические экземпляры доступны напрямую):
- Literata: `github.com/googlefonts/literata` → `fonts/ttf/Literata-{Regular,Italic,Medium,SemiBold}.ttf`
- JetBrains Mono: `github.com/google/fonts` → `ofl/jetbrainsmono/JetBrainsMono-{Regular,Medium}.ttf`
- Cormorant Garamond: в `github.com/google/fonts` → `ofl/cormorantgaramond` лежат только
  **переменные** `CormorantGaramond[wght].ttf` → статические экземпляры 500/600/700
  генерируем `fonttools.instancer` (см. шаг 4.1). Альтернатива: статические релизы
  `github.com/CatharsisFonts/Cormorant/releases`.

### 3.2 Детерминированный пакет ресурсов — `shared/build.gradle.kts`

Сгенерированный класс `Res` по умолчанию получает пакет из группы проекта, которая в
`shared` не задана явно. Фиксируем явно, чтобы импорты были стабильны:

```kotlin
compose {
    resources {
        packageOfResClass = "io.aequicor.magicpaper.resources"
        generateResClass = always
    }
}
```

### 3.3 Новый файл `ui/theme/Fonts.kt`

```kotlin
package io.aequicor.magicpaper.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.aequicor.magicpaper.resources.Res
import io.aequicor.magicpaper.resources.font.*
import org.jetbrains.compose.resources.Font

/** Дисплейная: бренд и крупные заголовки. Только для размеров ≥ 17sp. */
val MagicDisplay = FontFamily(
    Font(Res.font.cormorant_garamond_medium, FontWeight.Medium),
    Font(Res.font.cormorant_garamond_semibold, FontWeight.SemiBold),
    Font(Res.font.cormorant_garamond_bold, FontWeight.Bold),
)

/** Текстовая: всё, что читается долго. Покрывает латиницу/кириллицу/греческий. */
val MagicText = FontFamily(
    Font(Res.font.literata_regular, FontWeight.Normal),
    Font(Res.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(Res.font.literata_medium, FontWeight.Medium),
    Font(Res.font.literata_semibold, FontWeight.SemiBold),
)

/** Моно: код в чате и технические значения. */
val MagicCode = FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
)
```

### 3.4 Перезапись `MagicTypography` (полный набор ролей)

Сейчас заданы 5 ролей из 15 — остальные (например `bodySmall`, `titleSmall`, `labelMedium`)
молча рендерятся дефолтным sans Material, отсюда визуальная разношёрстность. Заполняем все:

```kotlin
val MagicTypography = Typography(
    // Дисплейные роли — только крупные размеры (ограничение Cormorant).
    displayLarge = TextStyle(MagicDisplay, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    displayMedium = TextStyle(MagicDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    displaySmall = TextStyle(MagicDisplay, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineLarge = TextStyle(MagicDisplay, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(MagicDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(MagicDisplay, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    // Заголовки. Увеличены на 1–2sp относительно старых: у гарамона
    // компактный кегль, компенсируем, чтобы иерархия читалась как раньше.
    titleLarge = TextStyle(MagicDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(MagicDisplay, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(MagicText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    // Текст — Literata: «книжное» чтение на пергаменте.
    bodyLarge = TextStyle(MagicText, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(MagicText, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(MagicText, fontSize = 13.sp, lineHeight = 19.sp),
    // Подписи и кнопки — тоже Literata, но средним весом: единый стиль без серифной «тяжести».
    labelLarge = TextStyle(MagicText, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(MagicText, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelSmall = TextStyle(MagicText, fontWeight = FontWeight.Medium, fontSize = 12.sp),
)
```

Замечания:
- `FontFamily.Serif` исчезает из кода полностью (поиск по репо подтверждает: только
  `MagicTheme.kt` и дефолт библиотеки markdown).
- `letterSpacing` для дисплейных ролей можно чуть увеличить (+0.2.sp) после визуальной
  пробы — оставить как шаг «полировка».

### 3.5 Код в чате — `ui/components/ChatMarkdown.kt`

`markdownTypography()` по умолчанию пинит `FontFamily.Monospace`; переопределяем на стек:

```kotlin
typography = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge,
    h2 = MaterialTheme.typography.titleMedium,
    h3 = MaterialTheme.typography.titleMedium,
    h4 = MaterialTheme.typography.bodyLarge,
    h5 = MaterialTheme.typography.bodyLarge,
    h6 = MaterialTheme.typography.bodyMedium,
    text = MaterialTheme.typography.bodyLarge,
    code = MaterialTheme.typography.bodyMedium.copy(fontFamily = MagicCode),
    inlineCode = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = MagicCode, fontSize = TextUnit.Unspecified
    ),
    quote = MaterialTheme.typography.bodyLarge.plus(SpanStyle(fontStyle = FontStyle.Italic)),
),
```

(Курсив цитат автоматически подтянет `literata_italic` — для этого он и в стеке.)

### 3.6 Декоративные глифы (☰ ✦ ⚙ ◷ ∑ ⌘ ✕ ▢ ─)

Их нет ни в одной из трёх гарнитур — они рендерятся системным фолбэком, как и сейчас.
Ничего не меняем; фиксируем в плане как осознанное решение (замена на иконки —
отдельная задача, материальные иконки в проекте не подключены).

---

## 4. Шаги реализации

### Шаг 4.1 — Ассеты

1. Скачать статические TTF Literata и JetBrains Mono (URL из п. 3.1).
2. Для Cormorant Garamond: скачать переменный `CormorantGaramond[wght].ttf` и снять
   статические экземпляры (инструмент уже есть локально — python3 + venv):
   ```bash
   uv venv /tmp/fontenv && source /tmp/fontenv/bin/activate && uv pip install fonttools
   fonttools instancer CormorantGaramond[wght].ttf wght=500 -o cormorant_garamond_medium.ttf
   fonttools instancer CormorantGaramond[wght].ttf wght=600 -o cormorant_garamond_semibold.ttf
   fonttools instancer CormorantGaramond[wght].ttf wght=700 -o cormorant_garamond_bold.ttf
   ```
   (Если инстансирование даст артефакты — взять статические файлы из релизов
   CatharsisFonts/Cormorant.)
3. Положить всё + `OFL.txt` (суммарный: тексты лицензий всех трёх семей) в
   `shared/src/commonMain/composeResources/font/`.
4. Опционально (веб-оптимизация, не блокирует): `pyftsubset` вырезать подмножество
   `latin+latin-ext+cyrillic+cyrillic-ext+greek+базовая пунктуация` — минус ~50% объёма.
   Делать после первого успешного запуска, сверив глифы «шш Щщ ё Ё і ї ѣ» и греческие.

### Шаг 4.2 — Код

1. `shared/build.gradle.kts`: блок `compose.resources` (п. 3.2).
2. Новый `ui/theme/Fonts.kt` (п. 3.3).
3. `MagicTheme.kt`: полный `MagicTypography` (п. 3.4), удалить `FontFamily.Serif`.
4. `ChatMarkdown.kt`: стили кода (п. 3.5).
5. Прогнать поиск `FontFamily.` по `shared/src` — убедиться, что иных употреблений нет.

### Шаг 4.3 — Проверка на всех таргетах

```bash
./gradlew :shared:compileKotlinJvm :shared:jvmTest        # компиляция + тесты
./gradlew :shared:compileKotlinJs :shared:compileKotlinWasmJs
./gradlew :androidApp:assembleDebug                       # упаковка ресурсов в APK
./gradlew :desktopApp:run                                 # визуальная проверка (в background)
./gradlew :webApp:wasmJsBrowserDevelopmentRun             # проверка в браузере
```

Чек-лист визуальной приёмки:
- [ ] Шапка «MagicPaper / Шалость удалась» — Cormorant, кириллица без «квадратиков».
- [ ] Ответ агента в чате — Literata; `код` и ```блоки``` — JetBrains Mono; цитата курсивом.
- [ ] Экраны настроек/доков/лавок — мелкие подписи читаются, иерархия размеров сохранилась.
- [ ] Английский и греческий текст в чате (проверочный запрос) рендерятся тем же стеком.
- [ ] Иероглифы (проверочный запрос «你好») не ломают строку — отработал системный фолбэк.
- [ ] На веб первая отрисовка не «прыгает» заметно после подгрузки font-ресурсов.

### Шаг 4.4 — Документация

- `README.md`: абзац «Типографика» (стек + лицензии) — требование OFL об атрибуции.

---

## 5. Фундамент i18n (рама для будущего перевода, отдельный объём)

Шрифты — половина задачи; сам перевод строк в этом обновлении **не выполняется**,
но решение по нему фиксируем сейчас, чтобы обновление шрифта не противоречило ему.

**Целевой механизм** — стандартный для Compose Multiplatform, без велосипедов:

1. Строки — в `shared/src/commonMain/composeResources/values/strings.xml`
   (общий) + `values-ru/…`, `values-en/…` и т.д.
2. Доступ: `stringResource(Res.string.xxx)`; выбор локали —
   `org.jetbrains.compose.resources.Localization` + `LocalLocalization`
   (переопределяется в `App()` поверх темы; системная локаль — дефолт).
3. Плейсхолдеры: `stringResource(Res.string.msg, args)`.

**Объём миграции (оценка):** 278 кириллических литералов в 24 файлах.
Реалистично тремя волнами:
- Волна 1 — хром и экраны: `App.kt`, `SettingsScreen`, `WelcomeScreen`, `CodingScreen`,
  `ChatScreen`, `SessionsPanel`, `DocsScreen`, `PluginsScreen` (~90 строк);
- Волна 2 — плагины: все `builtin/*` (~45 строк);
- Волна 3 — контент: `EmbeddedDocRepository`, `EmbeddedSkillCatalog`, системные
  промпты `MagicAgent`/`SkillEducator` (~140 строк). Контентные статьи переводятся
  не дословно, а адаптивно; промпты агента остаются функциональными.

**Важно:** контент (доки, каталог навыков) не обязан попадать в `strings.xml` —
это данные, их локализация делается полем локали в самих моделях (`DocArticle`,
`CatalogEntry`), а не ресурсами. Строгий критерий: в ресурсы уходит только
статический текст интерфейса.

---

## 6. Риски и грабли

| Риск | Вероятность | Митигация |
|---|---|---|
| Мелкий кегль Cormorant в шапке после замены | Средняя | Размеры уже подняты на 1–2sp (п. 3.4); приёмка по чек-листу |
| Пакет сгенерированного `Res` не совпадёт с импортом | Средняя | `packageOfResClass` задан явно (п. 3.2); после первой сборки проверить `build/generated/compose/resourceGenerator` |
| Веб: шрифт подгружается асинхронно, первая отрисовка на фолбэке | Высокая (штатное поведение) | Не блокирует; при заметном «прыжке» — добавить прелоад ресурсов в `webApp` (prefetch font-файлов) |
| Инстансирование переменного шрифта даст артефакты | Низкая | Запасной источник: статические релизы CatharsisFonts/Cormorant |
| Кэш ресурсов AGP «проглотит» файлы (известная грабля проекта) | Низкая | `./gradlew :androidApp:mergeDebugResources --rerun-tasks` |
| OFL-атрибуция забыта в дистрибутивах | Низкая | `OFL.txt` внутри ресурсов + абзац в README (шаг 4.4) |

## 7. Явно вне объёма этого обновления

- Перевод строк и выбор локали в настройках (раздел 5 — план, не реализация).
- Замена текстовых глифов (☰ ✦ ⚙) на иконки.
- Тёмная тема (палитра одна — светлая; шрифты от темы не зависят).
- Пользовательский выбор шрифта/кегля в настройках (возможное будущее:
  `AppSettings.fontSizeScale` — масштаб `sp` через `CompositionLocal`, тривиально
  ложится поверх этого стека).

## 8. Оценка трудозатрат

| Шаг | Затраты |
|---|---|
| Ассеты (скачивание, инстансирование, лицензии) | ~0.5 ч |
| Код (3 файла + build-скрипт) | ~0.5 ч |
| Сборки всех таргетов + визуальная приёмка | ~1 ч |
| Субсеттинг (опционально) | ~0.5 ч |
| Итого обновление шрифта | **~2–2.5 ч** |
| (Справочно) i18n волны 1–3 | отдельно, ~3–5 ч каждая |
