# File Templates

When a new Kotlin file is created, the extension displays a pickup drop-down of predefined file templates: Class, Interface, etc.

The templates can be configured using `Kotlin by Jetbrains/File Templates` settings.

Use `|` to specify the intial caret position.

Templates use the [Apache Velocity](https://velocity.apache.org/engine/devel/user-guide.html) language.

## Predefined Variables

| Variable | Description |
| --- | --- |
| `${PACKAGE_NAME}` | Name of the package in which the new file is created |
| `${USER}` | Current user system login name |
| `${DATE}` | Current system date |
| `${TIME}` | Current system time |
| `${YEAR}` | Current year |
| `${MONTH}` | Current month |
| `${MONTH_NAME_SHORT}` | First 3 letters of the current month name. Example: Jan, Feb, etc. |
| `${MONTH_NAME_FULL}` | Full name of the current month. Example: January, February, etc. |
| `${DAY}` | Current day of the month |
| `${DAY_NAME_SHORT}` | First 3 letters of the current day name. Example: Mon, Tue, etc. |
| `${DAY_NAME_FULL}` | Full name of the current day. Example: Monday, Tuesday, etc. |
| `${HOUR}` | Current hour |
| `${MINUTE}` | Current minute |
| `${PROJECT_NAME}` | Name of the current project |