# Генерация изображений и видео

## Проверено 18 сентября 2026

- JVM: 390 тестов, 0 ошибок — model 89, storage 39, AI 95, Paper 6, session 88, settings 56, app 17. В Paper включён opt-in native test: открытие на паузе, воспроизведение, перемотка через реальные controls и освобождение активного плеера; уход из композиции проверен после нажатия «Смотреть».
- `compileMigrationTargets` и `:desktopApp:test` — PASS: desktop, Android APK, JavaScript и Wasm distribution. Для линковки JavaScript потребовался разовый `-Pkotlin.daemon.jvmargs=-Xmx6144M --max-workers=1`; настройки проекта не менялись.
- Проверки архитектуры, Paper API и surface map — PASS. Осмотрены рендеры текста с медиа, параметров сессии и [всех состояний настроек](desktop-ui/MEDIA-SETTINGS-ACCEPTANCE.md), включая узкое окно и текст 200%. Статические рендеры не являются измерением FPS.
- macOS arm64: `:desktopApp:createDistributable` — PASS. В изолированной копии пакета отдельный тестовый main загружен через его штатный launcher, bundled JVM и bundled ComposeMediaPlayer; два цикла pause/play/seek/volume/mute/play/dispose прошли. Проверены путь загруженного JAR, наличие нативной dylib и отсутствие создания пользовательских данных. [Доказательство](../desktopApp/build/reports/media-native/evidence.json), [журнал](../desktopApp/build/reports/media-native/smoke.log). Это проверка содержимого пакета и нативного проигрывателя; установка DMG и весь пользовательский маршрут в установленном приложении отдельно не выполнялись.
- Windows native playback — NOT_RUN: Windows-хост отсутствует. Реальные платные OpenAI/DashScope запросы и live-запуски Pi/Codex с генерацией — NOT_RUN; HTTP, tool bridge и восстановление проверены с управляемыми адаптерами. Первое платное подтверждение доступности выполняется пользователем в настройках.

## Владельцы

- `core/model`: `GeneratedMedia`, `MediaAsset`, настройки и `TranscriptBlock`. История хранит ссылки и метаданные; байты не входят в runtime JSON.
- `core/ai/api`: `MediaGenerationGateway`; `core/ai/impl`: `HttpMediaGenerationGateway`. Адаптер отделяет OpenAI Images и Alibaba DashScope от модели чата. Видеогенерация использует DashScope; OpenAI-совместимый протокол здесь относится к изображениям.
- `feature/session`: `MediaGenerationService` / `DefaultMediaGenerationService` владеют проверкой подключения, долговечным журналом операций, загрузкой результата, восстановлением и usage. `ToolHost` подключает `image.generate` / `video.generate` с авторитетным контекстом сессии и существующим механизмом receipts.
- `core/storage`: `MediaStore` / JVM `FileMediaStore` сохраняют постоянные бинарные assets вне composer blobs. Идентичность asset — SHA-256. Выдаваемый агенту локальный путь принадлежит приложению.
- `feature/settings`: `MediaSettings`, `SettingsDrafts` и `MediaProfileArchive` владеют выбором моделей, проверкой и переносом профиля.
- `designSystem`: `PaperGeneratedMedia`, `PaperVideo` и ограниченные декодеры изображений. `GeneratedMediaProvider` связывает UI с существующими сервисом и storage; `GeneratedMediaView` загружает байты вне UI-потока.

## Подключение и жизненный цикл

В разделе «Настройки → Модели», в группах «Изображения» и «Видео», выбираются сохранённое подключение, модель и адрес API отдельно для изображения и видео. «Сохранить и проверить» действительно создаёт небольшой результат; пользователь заранее видит предупреждение о расходе API. Проверка привязана к effective configuration и credentials: изменение параметров не переносит прежний статус доступности.

Инструмент подключается только при доступном проверенном подключении и включённой настройке сессии. Настройка research принадлежит корневому исследованию и действует для его вопросов. Выключение влияет на последующие вызовы; не удаляет готовую историю. Предоставленная агентом строка не назначает владельца, путь проекта или runtime generation.

Принятый вызов создаёт media slot с постоянным id. `PENDING → GENERATING → DOWNLOADING → READY` меняет его содержимое на месте; известная ошибка даёт `FAILED`, неопределённый внешний исход — `UNKNOWN`. Отмена локального ожидания не доказывает отмену удалённого задания. Сохранённый provider job можно опросить повторно, но неизвестный исход не разрешает повторную платную отправку.

Генерация принадлежит application service и переживает уход с экрана. Восстановление читает журнал и опрашивает принятые задания. `operations` содержит последние сохранённые результаты; UI объединяет их с прежним snapshot по точному media id. Перед fork и export применяются `withGeneratedMedia` helpers, чтобы восстановленный результат вошёл в новый snapshot. Fork создаёт новые id блоков и медиа, сохраняя immutable asset; он не получает право продолжить исходную операцию.

Остановка ожидания агентом не останавливает принятый provider job: application service продолжает получать результат. Один проход ожидания ограничен 20 минутами; после этого сохраняются `UNKNOWN` и идентификатор задания для безопасного опроса. Проверка настройки и полномочий повторяется непосредственно перед отправкой. Отказ по запросу или ограничение частоты (`429` / throttling) даёт `FAILED` и не снимает ранее проверенную доступность модели. Потерянное подтверждение отправки сохраняет `UNKNOWN` и запрещает новую отправку с прежним идентификатором. Завершение media и обработка отмены ожидания согласуют один receipt: уже подтверждённый результат не превращается обратно в неизвестный.

Coding-агент получает метаданные и локальный путь; инструмент генерации сам не записывает файл в проект. При необходимости агент копирует файл обычными инструментами в текущую рабочую копию. `projectForExecution`, leases и `task.handoff` сохраняют владение worktree и доставку результата. Research не получает права изменения исходников.

## Книжная лента и воспроизведение

`CodingRunRecorder` фиксирует текст перед вызовом и обновляет тот же шаг при progress/result. Research сохраняет `ChatMessage.content` и `ChatSession.pendingContent`: последовательность Markdown и media. Положение иллюстрации определяется вызовом, а не совпадением имени файла или текста. Служебная activity остаётся отдельной; её сворачивание не скрывает иллюстрацию.

Иллюстрация занимает ширину чтения и сохраняет пропорции. При генерации фон мягко пульсирует; `paperAnimationEnabled` и Compose `MotionDurationScale=0` отключают это движение. Переход к готовому видео сохраняет место под controls. Ошибка отображается в исходном месте; «Проверить результат» опрашивает прежнее задание, «Загрузить снова» повторяет только чтение готового файла.

`PaperVideo` использует ComposeMediaPlayer `0.11.4`: AVPlayer в macOS и Media Foundation в Windows. Открытие истории загружает видео в `PAUSE`; действия «Смотреть», «Пауза», перемотка, отключение звука и громкость доступны внутри ленты. Уход из композиции освобождает native player. Android, браузер и Linux не заявлены как платформы полноценного playback; они имеют явное сообщение недоступности. Успешная JVM-компиляция не доказывает нативное воспроизведение на другой ОС.

`decodePaperMediaImage` проверяет MIME/сигнатуру и размеры PNG/JPEG/WebP/GIF/BMP до декодирования: до 12 MiB и 16 миллионов пикселей. `decodePaperThumbnailImage` сохраняет бюджет composer: 4 MiB, 4 миллиона пикселей, край 96 px. Не передавать произвольные Markdown URL/пути в media renderer.

## Перенос и удаление

`ProfileBundle` версии 3 содержит отдельный `generatedAssets`; base64 разрешён только в переносимом экспортном JSON. Старые профили без этого поля читаются с пустым списком. Экспортируются только assets экспортируемых чатов; проекты coding не добавлены в профиль. Импорт проверяет целостность и устанавливает assets до записей, которые на них ссылаются. Отсутствующий или повреждённый файл не превращается в успешный неполный экспорт.

Удаление записей должно учитывать общие ссылки forks и живые операции. Composer cleanup не владеет этими файлами. Reset сначала останавливает writers/runtime и только затем очищает media store.

## Приёмка и проверки

Маршруты: **Настройки → Модели → Изображения / Видео**; затем **Исследование → вопрос** или **Проекты и код → сессия**. Для проверки попросить текст до иллюстрации, саму иллюстрацию/видео и пояснение после неё; в меню редактора «Показать параметры» отдельно выключить изображения и видео; проверить уход/возврат, Stop/Continue и повторное открытие приложения.

| Состояние | Preview / проверка | Ожидаемое поведение |
| --- | --- | --- |
| Создание изображения | `PaperMediaGeneratingPreview`, группа `Generated media` | Подпись, animation-placeholder, устойчивое место между абзацами |
| Готовое изображение | `PaperMediaReadyPreview` | Полное изображение и подпись, без раскрытия tool card |
| Узкое окно / текст 2× | `PaperMediaReadyPreview` (360 dp) | Нет горизонтального переполнения и скрытых controls |
| Ошибка / неизвестный исход | `PaperMediaFailedPreview`, `PaperMediaUnknownPreview` | Честное состояние и безопасное действие восстановления |
| Создание видео | `PaperMediaVideoPreview` | Тот же reading slot и зарезервированная полоса controls |
| Готовое видео | `PaperVideoNativeTest`, opt-in | Начальная пауза, воспроизведение, seek, mute, повторное создание и dispose |
| Параметры сессии | `SessionMediaToolOptionsPreview`, `DisabledSessionMediaToolOptionsPreview`, группа `Session media tools` | Независимые разрешения image/video, доступность подключения, переход к моделям; узкое окно и текст 2× |
| Research / coding | `GeneratedMediaTranscriptRenderTest` | Текст → media → текст; placeholder доступен без раскрытия диагностики |

Preview-файл: `designSystem/src/commonMain/kotlin/io/aequicor/magicpaper/designsystem/PaperGeneratedMediaPreviews.kt`. Render-тесты сохраняют PNG в `designSystem/build/reports/generated-media/` и `feature/session/impl/build/reports/generated-media-transcript/`. Проверить общие leading edges, ширину чтения, подпись и место controls; статические PNG не устанавливают FPS или playback. Preview параметров: `feature/session/impl/src/commonMain/kotlin/io/aequicor/magicpaper/ui/screens/SessionMediaToolOptionsPreviews.kt`; render-тест `SessionMediaToolOptionsRenderTest` проверяет оба реальных composer-меню, независимые переключатели, переход в настройки и сохранность черновика. Native surface может не попасть в offscreen PNG; состояние playback доказывает отдельный тест player state.

Основные команды из корня репозитория:

```sh
python3 docs/desktop-ui/verify-design-system.py --self-test
python3 docs/desktop-ui/verify-map.py --self-test
./gradlew :core:model:jvmTest --tests '*GeneratedMediaTranscriptTest'
./gradlew :core:ai:impl:jvmTest --tests '*HttpMediaGenerationGatewayTest'
./gradlew :core:storage:impl:jvmTest --tests '*FileMediaStoreTest'
./gradlew :feature:session:impl:jvmTest --tests '*MediaGenerationServiceTest' --tests '*GeneratedMediaTranscriptRenderTest' --tests '*SessionMediaToolOptionsRenderTest' --tests '*CodingImageAttachmentRenderTest' --tests '*AttachmentPreviewQualityTest'
./gradlew :feature:session:impl:jvmTest --tests '*ToolReceiptIntegrityTest' --tests '*AgentToolBridgeTest'
./gradlew :feature:settings:impl:jvmTest --tests '*MediaSettingsPersistenceTest'
./gradlew :designSystem:jvmTest --tests '*PaperGeneratedMediaTest' --tests '*PaperMediaImageDecodingTest'
./gradlew -Pmagicpaper.media.native=true :designSystem:jvmTest --tests '*PaperVideoNativeTest'
```

Native smoke использует собственный локальный MP4 (H.264/AAC, 2 секунды), временную директорию и не обращается к API. Проверки providers через реальные credentials остаются явным пользовательским действием; обычные tests используют mock HTTP. Перед заявлением поддержки Windows нужны запуск native smoke и ручная проверка на Windows; результат macOS не заменяет их.

### Проверенная логика сервиса — 18 сентября 2026

На JVM в macOS прошли `MediaGenerationServiceTest` — **20/20**, `ToolReceiptIntegrityTest` — **22/22**, `AgentToolBridgeTest` — **4/4**. Это проверка детерминированных fixtures, без платных запросов:

- Доступность появляется только после проверки; смена credentials, несовместимый тип генерации и subscription-профиль не переиспользуют старое подтверждение. Локальное OpenAI-совместимое подключение без авторизации допустимо.
- Проверка показывает настоящий placeholder и progress; запоздалый результат прежней проверки не заменяет более новую даже при одинаковой конфигурации. Восстановление неизвестного probe обновляет тот же preview без второй отправки.
- Отмена локального ожидания оставляет принятое задание работающим; истечение срока сохраняет job для последующего опроса. Утраченное подтверждение отправки и неизвестное состояние задания не разрешают повторную генерацию.
- Отключённый инструмент отсутствует в каталоге, а разрешение, отозванное после записи намерения, предотвращает отправку. Отказ по содержимому и временное ограничение провайдера не вызывают quarantine и не отключают исправную модель.
- Восстановление обновляет receipt только для точной операции и runtime generation; готовый asset восстанавливает потерянную запись завершения. Подтверждённые `READY` и `FAILED`, опередившие отмену awaiter, не понижаются до `UNKNOWN` и не создают ложный quarantine.
- Ошибка сохранения при остановке не скрывает cancellation; удаление исходной сессии не удаляет общий immutable asset. Результат инструмента передаётся как JSON с локальным путём в обоих engine bridge, без подмены его недоверенным image-content.

Эти проверки подтверждают контракты и переходы состояния. Они не устанавливают доступность реального аккаунта провайдера, плавность UI или воспроизведение на Windows.
