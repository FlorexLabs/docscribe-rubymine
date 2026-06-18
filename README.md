<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon_256x256.png" alt="DocScribe Logo" width="128">
</p>

<h1 align="center">DocScribe for RubyMine</h1>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/XXXXX-docscribe">
    <img src="https://img.shields.io/jetbrains/plugin/v/XXXXX-docscribe?color=blue&label=Marketplace" alt="JetBrains Marketplace">
  </a>
  <a href="https://plugins.jetbrains.com/plugin/XXXXX-docscribe">
    <img src="https://img.shields.io/jetbrains/plugin/d/XXXXX-docscribe?color=green&label=Downloads" alt="Downloads">
  </a>
  <a href="https://github.com/FlorexLabs/docscribe-rubymine/actions/workflows/ci.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/FlorexLabs/docscribe-rubymine/ci.yml?branch=v0.1.0&label=CI" alt="CI">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/FlorexLabs/docscribe-rubymine" alt="License">
  </a>
  <a href="#requirements">
    <img src="https://img.shields.io/badge/RubyMine-2026.1%2B-blue" alt="RubyMine">
  </a>
  <a href="#requirements">
    <img src="https://img.shields.io/badge/ruby-%3E%3D%202.7-red" alt="Ruby">
  </a>
</p>

**DocScribe** is a RubyMine plugin that auto-generates inline YARD documentation for Ruby methods
using [docscribe](https://github.com/unurgunite/docscribe) — a Ruby gem that analyzes AST and suggests YARD-compatible
documentation. Compatible with **docscribe >= 1.5.0**.

> Also, available for [VS Code](https://github.com/FlorexLabs/docscribe-vscode) on
> the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=FlorexLabs.docscribe).

* [Features](#features)
* [Requirements](#requirements)
* [Installation](#installation)
  * [From JetBrains Marketplace](#from-jetbrains-marketplace)
  * [From disk](#from-disk)
* [Usage](#usage)
  * [Actions](#actions)
  * [Diagnostics](#diagnostics)
  * [Quick-Fix](#quick-fix)
  * [Settings](#settings)
* [Development](#development)
* [Architecture](#architecture)
* [License](#license)

## Features

- **Inline diagnostics** — undocumented methods highlighted in the editor on file open and save
- **Quick-fix intention** — lightbulb action to generate YARD documentation with one click
- **RBS type inference** — uses RBS signatures for accurate `@param` and `@return` types (when `gem "rbs"` is in your
  Gemfile)
- **Workspace-wide check** — scan all Ruby files in the project
- **Flexible strategies** — safe (document missing methods only) and aggressive (replace existing docs, preserve
  manual descriptions)
- **Update types from RBS** — refresh YARD docs from RBS signatures
- **Collapsible YARD docs** — fold all YARD comment blocks automatically on file open (configurable in settings)
- **Configurable** — bundle exec, custom command path, run on save, RBS toggle, omit boilerplate, hide comments
- **`.rake` support** — diagnostics and actions work on Rake files
- **JSON output** — uses `docscribe --format json` for reliable diagnostics parsing

## Requirements

- **RubyMine 2026.1+** (also works in IntelliJ IDEA with Ruby plugin)
- **Ruby** (>= 3.0) with Bundler
- **docscribe gem** >= 1.5.0

```bash
gem install docscribe
```

Or add to your Gemfile:

```ruby
gem "docscribe", group: :development
```

For RBS type inference:

```ruby
gem "rbs", group: :development
```

## Installation

### From JetBrains Marketplace

Install directly from the IDE: **Settings -> Plugins -> Marketplace** -> search "DocScribe".

Or download from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/XXXXX-docscribe).

### From disk

Download the latest release from [GitHub Releases](https://github.com/FlorexLabs/docscribe-rubymine/releases)
and install via Settings -> Plugins -> ⚙ -> Install Plugin from Disk.

## Usage

### Actions

| Action                                  | Shortcut                | Description                                           |
|-----------------------------------------|-------------------------|-------------------------------------------------------|
| **DocScribe -> Check Current File**     | `Ctrl+Shift+D` then `C` | Analyze the active Ruby file for undocumented methods |
| **DocScribe -> Check Entire Workspace** | `Ctrl+Shift+D` then `W` | Scan all Ruby files in the project                    |
| **DocScribe -> Apply Safe Fixes**       | `Ctrl+Shift+D` then `S` | Add docs to undocumented methods only                 |
| **DocScribe -> Apply Aggressive Fixes** | `Ctrl+Shift+D` then `A` | Replace all existing YARD docs                        |
| **DocScribe -> Update Types from RBS**  | —                       | Refresh YARD docs from RBS signatures                 |

All actions are available in the editor right-click menu under the **DocScribe** group.

### Diagnostics

Open a Ruby file — undocumented methods are underlined with a warning. Hover to see what's missing. Diagnostics update
automatically on file save and open.

### Quick-Fix

Click the lightbulb or press `Alt+Enter` on an annotated diagnostic and select **"Apply docscribe fix"** to
auto-generate documentation for that method.

### Settings

Navigate to **Settings -> Tools -> DocScribe**:

| Setting                  | Description                                  | Default     |
|--------------------------|----------------------------------------------|-------------|
| Command path             | Path to the docscribe executable             | `docscribe` |
| Use bundle exec          | Run via `bundle exec`                        | Off         |
| Use RBS type signatures  | Enable RBS-based type inference              | Off         |
| Omit boilerplate text    | Skip boilerplate in generated docs           | On          |
| Hide comments by default | Auto-fold YARD comment blocks on file open   | Off         |
| Run on save              | Automatically check file diagnostics on save | On          |

## Development

```bash
# Build the plugin
./gradlew buildPlugin

# Run in development IDE
./gradlew runIde

# Run tests
./gradlew test

# Check formatting and static analysis
./gradlew spotlessCheck detekt

# Verify compatibility
./gradlew verifyPlugin
```

Output: `build/distributions/docscribe-rubymine-*.zip`

## Architecture

- **docscribe-rubymine** — IntelliJ Platform plugin (Kotlin)
- **docscribe** — Ruby gem (CLI) — [GitHub](https://github.com/unurgunite/docscribe)

The plugin wraps the `docscribe` CLI via `GeneralCommandLine`/`CapturingProcessHandler` and parses its JSON
(RuboCop-compatible) output for inline diagnostics. Fixes are applied by running `docscribe` with `-a` (safe) or
`-A` (aggressive) flags.

## License

[MIT](./LICENSE)
