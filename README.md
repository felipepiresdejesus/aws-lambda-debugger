# AWS Lambda Test Tool - Rider Plugin

A production-ready IntelliJ Rider plugin for .NET developers building and debugging AWS Lambda functions locally with [Lambda Test Tool v2](https://github.com/aws/aws-lambda-dotnet/tree/master/Tools/LambdaTestTool).

![Build Status](https://github.com/felipepiresdejesus/aws-lambda-debugger/actions/workflows/build.yml/badge.svg)
![License](https://img.shields.io/github/license/felipepiresdejesus/aws-lambda-debugger)

## Features

- **One-Click Debugging** - Automatic debugger attachment with breakpoints, step-through, and variable inspection
- **Zero Configuration** - Automatically detects Lambda projects and validates prerequisites
- **Smart Process Management** - Intelligent lifecycle management with automatic cleanup
- **Cross-Platform** - Full support for Windows, macOS, and Linux
- **Auto-Install Tools** - Automatically installs `dotnet-lambda-test-tool` if missing
- **Clean UI** - Test Tool runs invisibly in background, only your Lambda process is visible
- **Test Event Generation** - Generate JSON test events for S3, API Gateway, SQS, SNS, and more
- **Startup Hook Debugging** - Uses a .NET startup hook to pause the Lambda process until the debugger attaches, ensuring breakpoints are hit from the very first line
- **Smart Diagnostics** - Automatic log analysis and actionable error suggestions when Lambda startup fails

## Supported IDEs

- **Rider 2024.3** and later
- **Rider 2025.x**

## Quick Start

### Installation

1. Open Rider
2. Go to **Settings** > **Plugins** > **Marketplace**
3. Search for "AWS Lambda"
4. Click **Install**
5. Restart Rider

Or install from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/com.felipejesus.aws-lambda-local-debugger)

### Basic Usage

1. Open your .NET Lambda project in Rider
2. Create a run configuration via **Run** > **Edit Configurations** > **+** > **AWS Lambda Test**
3. Select your Lambda project from the dropdown (the configuration name auto-populates)
4. Click the **Debug** button
5. The plugin will:
   - Build your project (saving unsaved files first)
   - Start the Lambda Test Tool in the background
   - Launch your Lambda function with the debug startup hook
   - Automatically attach Rider's .NET debugger
   - Show a progress bar during attachment

### Debug Mode

In debug mode, two tabs appear in the Debug tool window:

- **ProjectName - Console** - Shows the Lambda process stdout/stderr output
- **PID:ProjectName** - The Rider debug session with breakpoints, variables, and stepping

When you stop either tab, both the Lambda process and Test Tool are cleaned up automatically.

## Architecture

The plugin follows SOLID principles and clean architecture:

### Core Components

- **Services Layer** - Business logic for Test Tool management, project detection, and validation
- **Run Configuration** - IntelliJ Platform integration for run/debug configurations
- **Process Management** - Platform-aware process creation and lifecycle (Windows/macOS/Linux)
- **Debugger Integration** - Automatic attachment to Rider's .NET debugger via startup hook
- **Diagnostics** - Automatic log analysis for Lambda startup failures

### How Debugging Works

1. The plugin sets `DOTNET_STARTUP_HOOKS` to a bundled .NET startup hook DLL
2. The startup hook pauses the Lambda process (polling `Debugger.IsAttached`) for up to 30 seconds
3. The plugin finds the Lambda process PID and attaches Rider's `MsNetAttachDebugger`
4. Once attached, the startup hook releases and the Lambda function executes normally
5. Cleanup monitors ensure both the Lambda process and Test Tool are stopped when debugging ends

### Key Design Patterns

- **Dependency Injection** - Services injected via constructor for testability
- **Interface Segregation** - Clear separation of concerns with focused interfaces
- **Strategy Pattern** - Platform-specific process management strategies
- **Observer Pattern** - Status and output listeners for reactive updates

## Process Visibility

The Lambda Test Tool runs as a **detached background process**:

- Only your Lambda function appears in the Run/Debug window
- Test Tool runs silently in the background
- Automatically started and stopped with your Lambda function
- Keeps IDE focused on your Lambda code

## Documentation & Support

- [Testing Guide](./TESTING.md) - How to test the plugin in sandbox mode
- [Troubleshooting](./TROUBLESHOOTING.md) - Common issues and solutions
- [Changelog](./CHANGELOG.md) - Version history and changes
- [Releasing](/.github/RELEASING.md) - Release process documentation
- [Issues](https://github.com/felipepiresdejesus/aws-lambda-debugger/issues) - Report bugs or request features

## Quick Troubleshooting

### Lambda Project Not Detected?
- Ensure your project has `lambda-tools.json` or `serverless.template`
- Project must have a Lambda handler method
- Check project structure matches [AWS Lambda .NET template](https://docs.aws.amazon.com/lambda/latest/dg/lambda-csharp.html)

### Test Tool Won't Start?
```bash
# Install missing tool
dotnet tool install -g AWS.Lambda.TestTool

# Check installation
dotnet lambda --version
```

### Debugger Not Attaching?
- Verify Test Tool is running (check Run window)
- Set breakpoints in executable code
- Try manual attach: Run > Attach to Process > Select Lambda process

### Port Already in Use?
- Change port in Run Configuration > Settings
- Or kill process: `lsof -i :5050` then `kill -9 <PID>`

**For more troubleshooting, see [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)**

## Building

```bash
# Build the startup hook (requires .NET 6.0 SDK)
dotnet build startup-hook/StartupHook.csproj -c Release
cp startup-hook/bin/Release/net6.0/LambdaDebugStartupHook.dll src/main/resources/hooks/

# Build the plugin
./gradlew build

# Run automated tests
./gradlew test

# Run plugin in sandbox IDE
./gradlew runIde
```

## Project Structure

```
src/main/kotlin/com/aws/lambda/testtool/
├── actions/          # UI actions (context menus, etc.)
├── diagnostics/      # Lambda startup failure analysis
├── exceptions/       # Custom exception hierarchy
├── models/           # Data models and value objects
├── runconfig/        # Run configuration implementation
├── services/         # Business logic services
│   ├── debugger/     # Debugger attachment service
│   └── process/      # Process management
├── settings/         # Plugin settings
├── startup/          # Plugin startup activity
└── utils/            # Utility classes

startup-hook/         # .NET startup hook for debugger attachment
├── StartupHook.cs
└── StartupHook.csproj

src/main/resources/
├── hooks/            # Bundled startup hook DLL
└── META-INF/         # Plugin metadata
```

## License

This project is licensed under the MIT License - see [LICENSE](./LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

## Author

**Felipe Jesus**
- Email: contact@felipejesus.com
- GitHub: [@felipepiresdejesus](https://github.com/felipepiresdejesus)
