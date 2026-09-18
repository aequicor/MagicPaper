# Настройки генерации изображений и видео

Маршрут: **Настройки → Модели → Изображения и видео**. Группы изображений и видео сохраняют отдельные подключение, модель, адрес API и результат проверки. Проверка начинается только явным нажатием «Сохранить и проверить»; рядом указаны один пробный рисунок либо точная минимальная длительность видео и оплата по тарифу API.

Реальные формы: [MediaSettingsForm](../../feature/settings/impl/src/commonMain/kotlin/io/aequicor/magicpaper/ui/screens/MediaSettingsForm.kt). Изолированная галерея [MediaSettingsPreviews](../../feature/settings/impl/src/commonMain/kotlin/io/aequicor/magicpaper/ui/screens/MediaSettingsPreviews.kt), группа **Media settings**, не запускает сервис, сеть, сохранение или нативный проигрыватель.

| Preview | Размер / текст | Проверяемые области | Render |
|---|---|---|---|
| `MediaSettingsDefaultPreview` — Default | 900×1600 / 100% | Две независимые группы, модель, адрес, стоимость проверки, основное действие | [default.png](../../feature/settings/impl/build/reports/media-settings/default.png) |
| `MediaSettingsCheckedPreview` | 900×1600 / 100% | Два успешных подключения, изображение в форме, отключение | [checked.png](../../feature/settings/impl/build/reports/media-settings/checked.png) |
| `MediaSettingsCheckingPreview` | 390×1600 / 100% | Заблокированные кнопки повторной проверки, placeholder пробного результата | [checking.png](../../feature/settings/impl/build/reports/media-settings/checking.png) |
| `MediaSettingsErrorPreview` | 390×1600 / 100% | Понятная причина, сохранённые поля, доступное повторение | [error.png](../../feature/settings/impl/build/reports/media-settings/error.png) |
| `MediaSettingsUnsupportedPreview` | 390×1600 / 100% | Причина недоступности, отключённые поля и запуск, возможность убрать сохранённый выбор | [unsupported.png](../../feature/settings/impl/build/reports/media-settings/unsupported.png) |
| `MediaSettingsEmptyPreview` | 390×1600 / 100% | Приглашение добавить поставщика, пустые поля, проверка недоступна | [empty.png](../../feature/settings/impl/build/reports/media-settings/empty.png) |
| `MediaSettingsDefaultPreview` — Narrow | 390×1600 / 100% | Перенос строк, общие края полей, доступность кнопок | [narrow.png](../../feature/settings/impl/build/reports/media-settings/narrow.png) |
| `MediaSettingsDefaultPreview` — 200% text | 390×1600 / 200% | Полные подписи и двухстрочная кнопка, прокрутка до видео | [верх](../../feature/settings/impl/build/reports/media-settings/large-text.png), [низ](../../feature/settings/impl/build/reports/media-settings/large-text-bottom.png) |

Проверка: `:feature:settings:impl:jvmTest --tests '*MediaSettingsRenderTest'`. Тест проходит через настоящие Paper-компоненты и семантические действия: рендер не вызывает генерацию или запись; проверка/отключение адресованы своей группе; изменение черновика изображения во время старой проверки освобождает новое действие и не меняет видео. Дополнительно проверяются отсутствие обрезанных подписей, горизонтальный размер элементов и доступность нижнего действия после прокрутки при 200% текста.

Дополнительные реальные сцены `MediaConnectionPreview`: [ошибка чтения сохранённого файла](../../feature/settings/impl/build/reports/media-settings/preview-read-error.png) и [существующая операция после явного восстановления](../../feature/settings/impl/build/reports/media-settings/recovered-probe.png). Семантические тесты подтверждают, что «Проверить результат» появляется для неизвестного исхода, обращается только к существующей операции и блокирует повторный запуск до ответа. «Загрузить снова» повторяет только чтение сохранённого файла, без генерации.

Статус на 18 сентября 2026: **PASS**. Все 11 изображений в этой матрице и дополнительных сценах просмотрены после последнего запуска: подложка Paper непрозрачная, заголовок читается; поля и действия выровнены; текст ошибок переносится; основное действие целиком видно при 200%; до кнопки видео можно прокрутить. URL в однострочных полях прокручивается горизонтально, подписи и кнопки не обрезаются. `MediaSettingsRenderTest` — 4/4, `MediaSettingsPersistenceTest` — 8/8, полный `:feature:settings:impl:jvmTest` — 56/56. Лог запуска: `/private/tmp/magicpaper-media-settings-final.log`. Проверка `verify-map.py --self-test --write` также PASS.

Это Compose-рендер на macOS. Проверки установленного приложения на Windows и нативного проигрывания этим набором не подтверждаются; результат видео здесь покрывается границей формы и состоянием placeholder. Нативные проверки видео ведутся отдельно.
