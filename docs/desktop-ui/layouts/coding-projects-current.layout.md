---
screen: codingProjectsCurrent
sourceLocale: ru-RU
libraries:
  - id: paper
    source: paper
---

# Проекты и код — текущая реализация

## Frame: Window id window 1440 by 900 color #F3EBDD

### Frame: Toolbar id toolbar 1440 by 28 position 0 0

Text id toolbar_toggle «☰» 28 by 28 position 78 0 font «SansSerif» size 13 color #35242D text-align center text-valign center maxLines 1

Text id toolbar_back «‹» 28 by 28 position 106 0 font «SansSerif» size 15 color #35242D text-align center text-valign center maxLines 1

Text id toolbar_forward «›» 28 by 28 position 134 0 font «SansSerif» size 15 color #AAA39B text-align center text-valign center maxLines 1

Text id toolbar_app «MagicPaper» 90 by 16 position 170 6 font «SansSerif» size 13 weight 500 color #35242D maxLines 1

Text id toolbar_title «Проекты и код» 600 by 16 position 268 6 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

Ellipse id toolbar_usage 16 by 16 position 1354 6 color #EABBB1

Text id toolbar_settings «⚙» 28 by 28 position 1404 0 font «SansSerif» size 13 color #35242D text-align center text-valign center maxLines 1

### Frame: Sidebar id sidebar 272 by 872 position 0 28

Text id sidebar_title «Сессии» 240 by 24 position 16 12 font «SansSerif» size 18 semibold color #35242D maxLines 1

Rectangle id sidebar_divider_top 272 by 1 position 0 44 color #B7AD9D

Text id project_magicpaper_caret «▾» 12 by 18 position 16 56 font «SansSerif» size 13 color #625C70 maxLines 1

Text id project_magicpaper_name «MagicPaper» 180 by 18 position 38 56 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

Rectangle id session_row_selected_bg 256 by 40 position 8 84 color #E7DDE5 radius 6

Ellipse id session_row_selected_dot 8 by 8 position 28 100 color #8FC7A2

Text id session_row_selected_name «Правка навигации Decompose» 220 by 20 position 44 94 font «SansSerif» size 14 color #62558D maxLines 1

Ellipse id session_row_working_dot 8 by 8 position 28 140 color #E89B99

Text id session_row_working_name «Восстановление дочерних сессий» 220 by 20 position 44 134 font «SansSerif» size 14 color #35242D maxLines 1

Ellipse id session_row_queued_dot 8 by 8 position 28 180 color #E5E2E3

Text id session_row_queued_name «Макеты экрана Проекты и код» 220 by 20 position 44 174 font «SansSerif» size 14 color #35242D maxLines 1

Text id project_missionviz_caret «▸» 12 by 18 position 16 222 font «SansSerif» size 13 color #625C70 maxLines 1

Text id project_missionviz_name «mission-visualization» 180 by 18 position 38 222 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

Text id chats_section «Чаты» 120 by 18 position 16 262 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

Text id chat_row_glyph «✦» 12 by 20 position 28 290 font «SansSerif» size 13 color #625C70 maxLines 1

Text id chat_row_name «Идеи для онбординга» 208 by 20 position 44 290 font «SansSerif» size 14 color #35242D maxLines 1

Rectangle id sidebar_divider_bottom 272 by 1 position 0 798 color #B7AD9D

Text id sidebar_new_chat «✦ Новый чат» 256 by 28 position 8 806 font «SansSerif» size 13 weight 500 color #62558D text-align center text-valign center maxLines 1

Text id sidebar_new_project «📂 Новый проект» 256 by 28 position 8 836 font «SansSerif» size 13 weight 500 color #62558D text-align center text-valign center maxLines 1

Rectangle id drag_handle_divider 1 by 872 position 279 28 color #B7AD9D

Rectangle id drag_handle_grip 3 by 28 position 276 450 color #B7AD9D radius 2

### Frame: Session id session 1160 by 872 position 280 28

Text id session_heading_title «Правка навигации Decompose» 800 by 16 position 16 24 font «SansSerif» size 13 semibold color #35242D maxLines 1

Text id session_heading_subtitle «MagicPaper  /  Обычный режим» 800 by 16 position 16 44 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

#### Frame: User Message id user_message 520 by 44 position 632 88

Rectangle id user_message_bg 520 by 44 position 0 0 color #D8C9E7 radius 12

Text id user_message_text «Собери макеты экрана „Проекты и код“ в Paper Editor» 496 by 20 position 12 12 font «SansSerif» size 14 color #35242D maxLines 1

#### Frame: Tool Step id tool_step_read 680 by 32 position 8 144

Rectangle id tool_step_read_bg 680 by 32 position 0 0 color #FAF5ED radius 8

Text id tool_step_read_status «✓» 16 by 16 position 10 8 font «SansSerif» size 12 color #625C70 text-align center text-valign center maxLines 1

Text id tool_step_read_title «read tools/paper-plugin/src/main/kotlin/.../PaperFixtures.kt» 600 by 16 position 32 8 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

Text id tool_step_read_caret «▾» 16 by 16 position 652 8 font «SansSerif» size 12 color #625C70 text-align center text-valign center maxLines 1

#### Frame: Tool Step Exec id tool_step_exec 680 by 32 position 8 180

Rectangle id tool_step_exec_bg 680 by 32 position 0 0 color #FAF5ED radius 8

Text id tool_step_exec_status «✓» 16 by 16 position 10 8 font «SansSerif» size 12 color #625C70 text-align center text-valign center maxLines 1

Text id tool_step_exec_title «exec: PaperEditor --agent render screen.layout.md» 600 by 16 position 32 8 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

Text id tool_step_exec_caret «▾» 16 by 16 position 652 8 font «SansSerif» size 12 color #625C70 text-align center text-valign center maxLines 1

#### Frame: Agent Message id agent_message 760 by 132 position 8 224

Rectangle id agent_message_bg 760 by 132 position 0 0 color #DDD8E0 radius 12

Text id agent_message_line1 «Готово: два макета экрана лежат в docs/desktop-ui/layouts/.» 736 by 20 position 12 12 font «SansSerif» size 14 color #35242D maxLines 1

Text id agent_message_line2 «Каждый проверен компилятором SLM и настоящим рендером Paper,» 736 by 20 position 12 36 font «SansSerif» size 14 color #35242D maxLines 1

Text id agent_message_line3 «PNG возвращён в чат.» 736 by 20 position 12 60 font «SansSerif» size 14 color #35242D maxLines 1

Text id agent_message_meta «Действия агента: 2» 736 by 18 position 12 96 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

#### Frame: Composer id composer 1144 by 128 position 8 736

Rectangle id composer_bg 1144 by 128 position 0 0 gradient (linear from (0 0) to (0 1) stops (#F0E8DE at 0) (#E5DDD1 at 1)) radius 16

Text id composer_placeholder «Что нужно сделать?» 700 by 21 position 16 16 font «SansSerif» size 14 color #625C70 maxLines 1

Text id composer_tools «+» 32 by 32 position 16 80 font «SansSerif» size 18 color #62558D text-align center text-valign center maxLines 1

Text id composer_mode «Обычный режим» 140 by 16 position 56 88 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

Ellipse id composer_context_ring 18 by 18 position 820 87 color #E5DDD1 stroke #62558D 2

Text id composer_context_label «12%» 44 by 16 position 842 88 font «SansSerif» size 13 weight 500 color #35242D maxLines 1

Text id composer_model_name «pi · claude-sonnet-4.5» 150 by 14 position 880 82 font «SansSerif» size 13 weight 500 color #35242D maxLines 1

Text id composer_model_effort «high» 150 by 14 position 880 97 font «SansSerif» size 13 weight 500 color #625C70 maxLines 1

Rectangle id composer_divider 1 by 24 position 1040 84 color #B7AD9D

Instance id composer_send of PaperButton library paper 96 by 32 position 1048 80 props (text «Отправить») variant (platform macOS textScale «1» state NORMAL kind PRIMARY)
