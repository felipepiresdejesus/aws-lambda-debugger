# Troubleshooting Guide

This guide helps you resolve common issues when using the AWS Lambda Test Tool plugin.

## Installation & Setup

### Plugin Won't Install

**Issue**: Plugin fails to install from JetBrains Marketplace

**Solutions**:
1. Ensure you're using a supported Rider version (RD-2024.1 or later)
2. Check your internet connection
3. Try restarting Rider
4. Check the marketplace for compatibility with your IDE version

### Supported Rider Versions

- ✅ Rider 2024.3 and later
- ✅ Rider 2025.x

For older versions, the plugin may still work but is not officially supported.

---

## Lambda Project Detection

### Lambda Project Not Detected

**Issue**: The plugin doesn't recognize your .NET Lambda project

**Causes & Solutions**:
1. **Missing `lambda-tools.json`**
   - The plugin looks for `lambda-tools.json` in project roots
   - Solution: Ensure your project was created with `dotnet new lambda.EmptyServerless`
   
2. **Incorrect project structure**
   - Lambda projects should have a handler method (async or sync)
   - Solution: Check that your project matches the [AWS Lambda .NET guide](https://docs.aws.amazon.com/lambda/latest/dg/lambda-csharp.html)

3. **Project in non-standard location**
   - Solution: Move project to standard workspace location

**How to verify**:
```bash
# Check if aws-lambda-tools-defaults.json exists
find . -name "*lambda*.json" -o -name "*serverless*.json"
```

---

## Prerequisites Validation

### Warnings About Missing Prerequisites

**Issue**: Plugin shows warnings about missing .NET or AWS tools

**Warnings you may see**:
- "AWS Lambda Tool is not installed"
- "Dotnet CLI not found"
- ".NET 8.0 SDK not available"

**Solutions**:
1. **Install AWS Lambda Tools**
   ```bash
   dotnet tool install -g Amazon.Lambda.Tools
   # Or update existing:
   dotnet tool update -g Amazon.Lambda.Tools
   ```

2. **Install required .NET version**
   - Check your project's `Dockerfile` or `.csproj` for required SDK
   - Download from [dotnet.microsoft.com](https://dotnet.microsoft.com/download)

3. **Verify installations**
   ```bash
   dotnet --version
   dotnet lambda --version
   ```

---

## Running & Debugging

### Lambda Function Won't Start

**Issue**: Clicking "Run" or "Debug" doesn't start the Lambda function

**Symptoms**:
- No process appears in Run window
- Test Tool port shows as unavailable
- Error in IDE notifications

**Solutions**:
1. **Check port availability**
   - Default port is 5050
   - If in use, change in run configuration → Settings
   - Verify with: `netstat -an | grep 5050`

2. **Verify Test Tool installation**
   - Plugin auto-installs `dotnet-lambda-test-tool`
   - Check if installed: `dotnet tool list -g | grep lambda-test-tool`
   - Manually install if missing:
     ```bash
     dotnet tool install -g AWS.Lambda.TestTool
     ```

3. **Check working directory**
   - Run configuration must point to Lambda project directory
   - Edit configuration and verify "Working Directory"

4. **Enable debug logging**
   - Help → Diagnostic Tools → Debug Log Settings
   - Search for "AWS Lambda" to see plugin logs
   - Look for errors about Test Tool startup

### Debugger Won't Attach

**Issue**: Running in Debug mode but breakpoints don't work

**Solutions**:
1. **Verify .NET debugger is installed**
   - Rider should auto-detect installed debuggers
   - Settings → Build, Execution, Deployment → Debugger → Debug Configuration

2. **Check breakpoint is valid**
   - Breakpoint must be in executable code (not comments/properties)
   - Ensure line is reachable from your Lambda handler

3. **Verify Lambda process is running**
   - Check IDE's Run window to see process listed
   - If not listed, Lambda didn't start (see "Lambda Function Won't Start")

4. **Try manual debugging**
   - If auto-attach fails, attach manually:
   - Run → Attach to Process
   - Select your Lambda process
   - Set breakpoints and continue

---

## Performance Issues

### Test Tool Slow or Hanging

**Issue**: Lambda function or Test Tool appears to hang

**Solutions**:
1. **Check system resources**
   - Monitor CPU and memory usage
   - Lambda functions can be resource-intensive
   - Close unnecessary applications

2. **Increase timeout**
   - Configuration → Edit → Environment Variables
   - Add: `LAMBDA_TIMEOUT_SECONDS=30`

3. **Check logs for errors**
   - Help → Show Log in Explorer
   - Look for `TestTool` or `Lambda` related errors
   - Check for "timeout" or "deadlock" messages

### IDE Becomes Unresponsive

**Issue**: Rider freezes when starting/stopping Lambda functions

**Solutions**:
1. **Disable auto-open browser**
   - Run Configuration → Settings → uncheck "Auto-open browser"

2. **Increase IDE heap size**
   - Edit → Edit Custom VM Options
   - Increase `-Xmx` value (e.g., `-Xmx4096m`)
   - Restart Rider

3. **Check for orphaned processes**
   - Sometimes Test Tool processes aren't cleaned up
   - Kill manually:
     ```bash
     # Windows
     taskkill /F /IM dotnet-lambda-test-tool.exe
     
     # macOS/Linux
     pkill -f "lambda-test-tool"
     ```

---

## Cleanup & Reset

### Orphaned Test Tool Processes

**Issue**: Test Tool processes remain running even after stopping

**Symptoms**:
- Port still in use after stopping Lambda
- Multiple dotnet processes visible
- Can't start new Lambda function on same port

**Solution**:
1. **Stop manually**:
   ```bash
   # Find process on port 5050
   lsof -i :5050
   
   # Kill process
   kill -9 <PID>
   ```

2. **Reset plugin state**
   - File → Invalidate Caches → Invalidate and Restart
   - This clears cached state but keeps project data

### Reset Plugin Settings

**To clear all plugin settings**:
```bash
# macOS/Linux
rm -rf ~/.config/JetBrains/Rider*/system/plugins

# Windows
rmdir %APPDATA%\JetBrains\Rider*\system\plugins
```

Then restart Rider and reconfigure.

---

## Reporting Issues

### Found a Bug?

1. **Check existing issues**: [GitHub Issues](https://github.com/felipepiresdejesus/aws-lambda-debugger/issues)

2. **Gather debug logs**:
   - Help → Show Log in Finder/Explorer
   - Search for entries with "AWS Lambda" or "Lambda Test"
   - Attach relevant logs to issue

3. **Create issue with**:
   - Rider version
   - .NET version
   - Steps to reproduce
   - Error messages or logs
   - Expected vs actual behavior

### Getting Help

- 📧 Email: contact@felipejesus.com
- 🐛 Issues: [GitHub Issues](https://github.com/felipepiresdejesus/aws-lambda-debugger/issues)
- 📖 Documentation: See README.md

---

## Advanced Configuration

### Environment Variables for Testing

Pass environment variables to your Lambda:

1. Open Run Configuration
2. Edit → Environment Variables tab
3. Add your variables (available to Lambda function)

Example:
```
AWS_REGION=us-east-1
CUSTOM_VAR=my-value
```

### Custom Port Configuration

To use a different port:

1. Run Configuration → Edit Configuration
2. Change "Port" field (default: 5050)
3. Ensure port is available: `netstat -an | grep <PORT>`

### Logging Configuration

To see more detailed logs:

```bash
# Enable detailed Test Tool logging
export LAMBDA_TEST_TOOL_LOG_LEVEL=Debug
```

Then run Lambda function.

---

## FAQ

**Q: Can I debug multiple Lambda functions simultaneously?**
A: Create multiple run configurations with different ports. Start them in separate terminal instances.

**Q: Does this plugin work with async handlers?**
A: Yes, fully supported.

**Q: Can I use this with Lambda Layers?**
A: Layers are supported through the `sam-tests.json` or `lambda-tools.json` configuration.

**Q: What about Lambda extensions?**
A: Basic support - extensions should run normally. Performance impact depends on extension complexity.

---

## Still Having Issues?

If this guide doesn't help:

1. Check the [GitHub Issues](https://github.com/felipepiresdejesus/aws-lambda-debugger/issues) for similar reports
2. Enable debug logging (Help → Debug Log Settings)
3. Search logs for errors
4. [Create a new issue](https://github.com/felipepiresdejesus/aws-lambda-debugger/issues/new) with:
   - Clear description of problem
   - Steps to reproduce
   - Relevant logs
   - Rider & .NET versions

Your feedback helps improve the plugin!
