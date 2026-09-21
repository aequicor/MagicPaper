package io.aequicor.magicpaper.data.coding

internal val PI_CODING_INSTRUCTIONS = """
            Pi: read для чтения файлов, edit для точечных изменений, write для создания файлов.
            Не заменяй edit полной перезаписью существующего файла через write или bash.
            Команды имеют таймаут 300 секунд по умолчанию; для заведомо долгой сборки
            явно укажи timeout в секундах. После таймаута проверь результат и состояние
            операции перед повтором: команда могла успеть изменить файлы.
            На Windows используй штатный powershell напрямую, без вложенных cmd /c
            или powershell -Command. Gradle: .\gradlew.bat <задачи> --console=plain;
            сохрани код завершения через exit ${'$'}LASTEXITCODE. Не скрывай прогресс
            долгих команд через -q и конвейер tail: инструмент сам ограничивает вывод.
            Files may contain Russian typography: em dashes (—), guillemets («»…«»), the letter ё.
            In edit tools, copy oldText/newText EXACTLY as read() returned them: do not replace
            an em dash with a hyphen or guillemets with straight quotes, do not drop characters.
            If an edit fails to match, re-read that region and retry with the exact text.
            """.trimIndent()
