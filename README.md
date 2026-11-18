# CSV Test Navigator

An IntelliJ plugin for test-case CSV files. Ctrl+Click (or Ctrl+B) on a process or data name jumps to its row.
Process names are green, data names blue, and names whose target can't be found are red (hover for where the
plugin looked; F2 jumps to the next one).

Every column whose name starts with `Process` or `Data` is handled, so a test case can chain several steps.
`DataN` uses the process in `ProcessN` (or, if there is no matching number, the nearest Process column to its left).

```
project/
├─ config/ProcessDetails.csv     name,description,...      <- one row per process, name in column 1
├─ testData/Login.csv            key,user,password,...     <- data for process "Login", key in column 1
├─ testData/shop/PlaceOrder.csv  (one CSV per process, sub-folders allowed)
└─ tests/LoginTests.csv          Id,Process1,Data1,Process2,Data2
                                 TC1,Login,user1,PlaceOrder,order1
                                     │     │     │          └─ ../testData/**/PlaceOrder.csv row "order1"
                                     │     │     └─ ProcessDetails.csv row "PlaceOrder"
                                     │     └─ ../testData/**/Login.csv row "user1"
                                     └─ ProcessDetails.csv row "Login"
```

## Configure

**Settings | Tools | CSV Test Navigator** (per project, saved to `.idea/csvTestNavigator.xml`):

- **Column mappings**: one per line, `column = path[, path2]`. Column names are matched case-insensitively;
  `column*` matches every column starting with that name, and an exact name beats a `*` rule.
  - Paths starting with `.` are relative to the test case file's folder; others to the project root.
  - `{column}` is replaced by that column's value in the same row. `{process*}` means "the Process column paired
    with this one": `Process2` for a `Data2` cell, else the nearest Process column to its left.
  - `**` matches any number of folders.
  - Path to a **CSV file**: jumps to the row whose first column equals the cell value.
  - Path to a **folder**: jumps to a file with that name (any extension), or to a row whose first column equals
    the cell value in any CSV file in the folder or its sub-folders.

  Default:
  ```
  process* = ../config/ProcessDetails.csv
  data* = ../testData/**/{process*}.csv
  ```
- **CSV file extensions**: default `csv`.
- **Header row**: the row of the test case file that holds the column names (default 1).
- **Colours**: Settings | Editor | Color Scheme | CSV Test Navigator ("Process name", "Data name",
  "Missing reference"). Columns starting with `Process` use the process colour; other mapped columns the data colour.

An exact match wins over a case-insensitive one. If several rows or files match, IntelliJ shows a chooser.
The delimiter (`,` `;` tab `|`) is detected from each file's first row, and quoted cells are supported.
Only CSVs whose header has a mapped column are highlighted.

## Build / install

Needs JDK 21.

```
./gradlew buildPlugin    # -> build/distributions/csv-test-navigator-1.0.0.zip
./gradlew test
./gradlew runIde --args="<path to a project>"   # sandbox IDE with the plugin loaded
./gradlew test "-PlocalIde=C:/path/to/IntelliJ IDEA 2026.3"   # test against an installed IDE
```

Install: **Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick the zip, then restart the IDE.
