# Task IntelliJ Run Configuration Plugin

IntelliJ Run configuration plugin for [Task](https://taskfile.dev/).

![](docs/screenshot.png)

## Build

```bash
$ ./gradlew build
```

## Usage

1. Install [Taskfile Plugin](https://plugins.jetbrains.com/plugin/17058-taskfile) by searching `Taskfile` in plugin marketplace.
2. Open 'Run/Debug Configurations'.
3. Add `Taskfile`:
  * Taskfile: `Taskfile.yml` file to use. 
  * Task: Input task name to run.
