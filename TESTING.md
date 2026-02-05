# Testing the Plugin

## Running the Plugin in Rider (Sandbox Mode)

To test the plugin without installing it, use the `runIde` Gradle task. This will launch Rider with your plugin loaded in a sandbox environment.

### Quick Start

```bash
./gradlew runIde
```

This will:
1. Build the plugin
2. Launch Rider in a sandbox environment
3. Load your plugin automatically
4. Allow you to test all plugin features

### Using the Sandbox

- The sandbox is isolated from your main Rider installation
- All plugin changes are immediately available after rebuilding
- You can open test Lambda projects in the sandbox Rider instance
- Debug the plugin itself by setting breakpoints in your plugin code

### Stopping the Sandbox

- Close the Rider window that opens
- Or press `Ctrl+C` in the terminal where `runIde` is running

## Generating Test Event Files

The plugin includes a test event generator that can create JSON templates for common AWS Lambda event types.

### Using the Action

1. Right-click on a Lambda project in the Project view
2. Select **AWS Lambda** → **Generate Test Event...**
3. Choose the event type (S3, API Gateway, SQS, etc.)
4. Select where to save the JSON file
5. The file will be generated and opened in the editor

### Supported Event Types

- **S3** - S3 bucket events (ObjectCreated, ObjectRemoved, etc.)
- **API Gateway** - REST API events
- **API Gateway V2** - HTTP API events
- **SQS** - Simple Queue Service events
- **SNS** - Simple Notification Service events
- **DynamoDB** - DynamoDB stream events
- **Kinesis** - Kinesis stream events
- **Scheduled** - EventBridge scheduled events
- **Simple** - Simple JSON object for custom events

### Generated Files

Test event files are saved as JSON and can be:
- Used directly with the Lambda Test Tool
- Modified to match your specific test scenarios
- Stored in a `test-events` directory in your project

### Example Usage

After generating a test event file:

1. Build your Lambda project
2. Create a Lambda Test run configuration
3. Run or debug the configuration
4. Use the Lambda Test Tool web UI to invoke your function with the generated event

## Building and Installing

To build the plugin for installation:

```bash
./gradlew buildPlugin
```

The plugin ZIP will be created at:
```
build/distributions/aws-lambda-test-tool-plugin-1.0.0.zip
```

Install it in Rider via:
- **Settings** → **Plugins** → **Install Plugin from Disk...**
