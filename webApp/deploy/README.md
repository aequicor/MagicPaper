# Публикация web-клиента

Приложение публикуется в корне origin (`https://paper.example/`). HTML base,
webpack publicPath и manifest start_url указывают на `/`, поэтому холодный вход
по `/settings/models` загружает `/webApp.js`, CSS, иконки и ленивые JS/Wasm chunks
из корня. Размещение под `/magicpaper/` требует согласованного изменения base,
publicPath, manifest и browser history basePath.

Соберите один вариант:

```sh
./gradlew :webApp:jsBrowserDistribution
# либо
./gradlew :webApp:wasmJsBrowserDistribution
```

Публикуйте всё содержимое `webApp/build/dist/js/productionExecutable/` либо
`webApp/build/dist/wasmJs/productionExecutable/`, включая `composeResources`,
JS/Wasm файлы, иконки и manifest. Используйте HTTPS; HTTP подходит для localhost.

Для nginx используйте соседний `nginx.conf` внутри секции `http`, указав каталог
сборки и параметры сервера. Для статического хостинга с поддержкой `_redirects`
правила уже включены в ресурсы и попадут в distribution. Другим серверам нужны
такие же rewrites: `/chat`, `/projects`, `/settings`, `/docs`, `/plugins` и их
вложенные пути отдают `index.html` со статусом 200, сохраняя исходный URL. Запросы
к отсутствующим ресурсам должны оставаться 404. Webpack dev server уже настроен
аналогично; обычный сервер статических файлов без fallback недостаточен.

После публикации откройте в новой вкладке `/settings/models` и вложенный путь
проекта, затем перезагрузите страницу. В Network проверьте загрузку `/webApp.js`
и `.wasm` с правильными MIME-типами, отсутствие `/settings/webApp.js`, а также
Back/Forward и повторный запуск. Onboarding может отложить переход до завершения.
