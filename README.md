# AWS Lambda Test Tool - Rider Plugin

A production-ready IntelliJ Rider plugin for .NET developers building and debugging AWS Lambda functions locally with [Lambda Test Tool v2](https://github.com/aws/aws-lambda-dotnet/tree/master/Tools/LambdaTestTool).

![Build Status](https://github.com/felipepiresdejesus/aws-lambda-debugger/actions/workflows/build.yml/badge.svg)
![License](https://img.shields.io/github/license/felipepiresdejesus/aws-lambda-debugger)

## Features

- ⚡ **One-Click Debugging** - Automatic debugger attachment with breakpoints, step-through, and variable inspection
- 🔧 **Zero Configuration** - Automatically detects Lambda projects and validates prerequisites
- 🚀 **Smart Process Management** - Intelligent lifecycle management with automatic cleanup
- 🔄 **Cross-Platform** - Full support for Windows, macOS, and Linux
- 📦 **Auto-Install Tools** - Automatically installs `dotnet-lambda-test-tool` if missing
- 🎯 **Clean UI** - Test Tool runs invisibly in background, only your Lambda process is visible
- 📝 **Test Event Generation** - Generate JSON test events for S3, API Gateway, SQS, SNS, and more

## Supported IDEs

- **Rider 2024.3** and later
- **Rider 2025.x**

## Quick Start

### Installation

1. Open Rider
2. Go to **Settings** → **Plugins** → **Marketplace**
3. Search for "AWS Lambda"
4. Click **Install**
5. Restart Rider

Or install from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/com.felipejesus.aws-lambda-local-debugger)

### Basic Usage

1. Open your .NET Lambda project in Rider
2. Click the run/debug button next to your Lambda handler method
3. The plugin will:
   - ✓ Validate prerequisites
   - ✓ Start the Lambda Test Tool
   - ✓ Attach the debugger automatically
   - ✓ Open your browser to the test tool (optional)

## Architecture

The plugin follows SOLID principles and clean architecture:

### Core Components

- **Services Layer** - Business logic for Test Tool management, project detection, and validation
- **Run Configuration** - IntelliJ Platform integration for run/debug configurations  
- **Process Management** - Platform-aware process creation and lifecycle (Windows/macOS/Linux)
- **Debugger Integration** - Automatic attachment to Rider's .NET debugger

### Key Design Patterns

- **Dependency Injection** - Services injected via constructor for testability
- **Interface Segregation** - Clear separation of concerns with focused interfaces
- **Strategy Pattern** - Platform-specific process management strategies
- **Observer Pattern** - Status and output listeners for reactive updates

## Process Visibility

The Lambda Test Tool runs as a **detached background process**:

- ✓ Only your Lambda function appears in the Run/Debug window
- ✓ Test Tool runs silently in the background
- ✓ Automatically started and stopped with your Lambda function
- ✓ Keeps IDE focused on your Lambda code

## Documentation & Support

- 📖 [Testing Guide](./TESTING.md) - How to test the plugin in sandbox mode
- 📋 [Changelog](./CHANGELOG.md) - Version history and changes
- 🐛 [Issues](https://github.com/felipepiresdejesus/aws-lambda-debugger/issues) - Report bugs or request features
- 📧 Contact: contact@felipejesus.com

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
- Try manual attach: Run → Attach to Process → Select Lambda process

### Port Already in Use?
- Change port in Run Configuration → Settings
- Or kill process: `lsof -i :5050` then `kill -9 <PID>`

**For more troubleshooting, see [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)**

## Building

```bash
./gradlew build
```

## Testing

```bash
# Run automated tests
./gradlew test

# Run plugin in sandbox IDE
./gradlew runIde
```

## License

This project is licensed under the MIT License - see [LICENSE](./LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

## Author

**Felipe Jesus**
- Email: contact@felipejesus.com
- GitHub: [@felipepiresdejesus](https://github.com/felipepiresdejesus)

## Project Structure

```bash
./gradlew test
```

## Project Structure

```
src/main/kotlin/com/aws/lambda/testtool/
├── actions/          # UI actions (context menus, etc.)
├── exceptions/       # Custom exception hierarchy
├── models/           # Data models and value objects
├── runconfig/        # Run configuration implementation
├── services/          # Business logic services
│   ├── debugger/     # Debugger attachment service
│   └── process/       # Process management
├── settings/          # Plugin settings
└── utils/             # Utility classes
```

## License

Copyright (c) 2026
