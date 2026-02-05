package com.aws.lambda.testtool.utils

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Utility for generating test event JSON files for common AWS Lambda event types.
 * These can be used with the Lambda Test Tool to test Lambda functions locally.
 */
object LambdaEventGenerator {
    private val LOG = Logger.getInstance(LambdaEventGenerator::class.java)
    
    /**
     * Generates a test event JSON file for the specified event type.
     * 
     * @param eventType The type of Lambda event (S3, APIGateway, etc.)
     * @param outputFile The file where the event JSON will be written
     * @param customizations Optional map of customizations to apply to the template
     */
    fun generateEventFile(
        eventType: LambdaEventType,
        outputFile: File,
        customizations: Map<String, String> = emptyMap()
    ): Boolean {
        return try {
            val template = getEventTemplate(eventType, customizations)
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(template)
            LOG.info("Generated ${eventType.name} event file: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            LOG.error("Failed to generate event file: ${outputFile.absolutePath}", e)
            false
        }
    }
    
    /**
     * Gets the JSON template for a specific event type.
     */
    private fun getEventTemplate(eventType: LambdaEventType, customizations: Map<String, String>): String {
        val template = when (eventType) {
            LambdaEventType.S3 -> getS3EventTemplate()
            LambdaEventType.APIGateway -> getAPIGatewayEventTemplate()
            LambdaEventType.APIGatewayV2 -> getAPIGatewayV2EventTemplate()
            LambdaEventType.SQS -> getSQSEventTemplate()
            LambdaEventType.SNS -> getSNSEventTemplate()
            LambdaEventType.DynamoDB -> getDynamoDBEventTemplate()
            LambdaEventType.Kinesis -> getKinesisEventTemplate()
            LambdaEventType.Scheduled -> getScheduledEventTemplate()
            LambdaEventType.Simple -> getSimpleEventTemplate()
        }
        
        // Apply customizations (simple string replacement)
        return customizations.entries.fold(template) { acc, (key, value) ->
            acc.replace("{{$key}}", value)
        }
    }
    
    private fun getS3EventTemplate(): String = """
{
  "Records": [
    {
      "eventVersion": "2.1",
      "eventSource": "aws:s3",
      "awsRegion": "{{region}}",
      "eventTime": "{{timestamp}}",
      "eventName": "ObjectCreated:Put",
      "userIdentity": {
        "principalId": "EXAMPLE"
      },
      "requestParameters": {
        "sourceIPAddress": "127.0.0.1"
      },
      "responseElements": {
        "x-amz-request-id": "EXAMPLE123456789",
        "x-amz-id-2": "EXAMPLE123/5678abcdefghijklambdaisawesome/mnopqrstuvwxyzABCDEFGH"
      },
      "s3": {
        "s3SchemaVersion": "1.0",
        "configurationId": "testConfigRule",
        "bucket": {
          "name": "{{bucketName}}",
          "ownerIdentity": {
            "principalId": "EXAMPLE"
          },
          "arn": "arn:aws:s3:::{{bucketName}}"
        },
        "object": {
          "key": "{{objectKey}}",
          "size": 1024,
          "eTag": "0123456789abcdef0123456789abcdef",
          "sequencer": "0A1B2C3D4E5F6G7H"
        }
      }
    }
  ]
}
""".trimIndent()
    
    private fun getAPIGatewayEventTemplate(): String = """
{
  "resource": "/{{path}}",
  "path": "/{{path}}",
  "httpMethod": "{{method}}",
  "headers": {
    "Accept": "application/json",
    "Content-Type": "application/json"
  },
  "multiValueHeaders": {
    "Accept": ["application/json"],
    "Content-Type": ["application/json"]
  },
  "queryStringParameters": null,
  "multiValueQueryStringParameters": null,
  "pathParameters": null,
  "stageVariables": null,
  "requestContext": {
    "resourceId": "123456",
    "resourcePath": "/{{path}}",
    "httpMethod": "{{method}}",
    "requestId": "c6af9ac6-7b61-11e6-9a41-93e8deadbeef",
    "accountId": "123456789012",
    "apiId": "1234567890",
    "stage": "{{stage}}",
    "identity": {
      "cognitoIdentityPoolId": null,
      "accountId": null,
      "cognitoIdentityId": null,
      "caller": null,
      "apiKey": null,
      "sourceIp": "127.0.0.1",
      "accessKey": null,
      "cognitoAuthenticationType": null,
      "cognitoAuthenticationProvider": null,
      "userArn": null,
      "userAgent": "Custom User Agent String",
      "user": null
    },
    "authorizer": null,
    "path": "/{{path}}",
    "protocol": "HTTP/1.1"
  },
  "body": "{{body}}",
  "isBase64Encoded": false
}
""".trimIndent()
    
    private fun getAPIGatewayV2EventTemplate(): String = """
{
  "version": "2.0",
  "routeKey": "{{method}} /{{path}}",
  "rawPath": "/{{path}}",
  "rawQueryString": "",
  "headers": {
    "accept": "application/json",
    "content-type": "application/json",
    "host": "{{host}}",
    "user-agent": "Custom User Agent String",
    "x-amzn-trace-id": "Root=1-5e272390-8c398be037738dc11872b082"
  },
  "requestContext": {
    "accountId": "123456789012",
    "apiId": "1234567890",
    "domainName": "{{host}}",
    "domainPrefix": "{{domainPrefix}}",
    "http": {
      "method": "{{method}}",
      "path": "/{{path}}",
      "protocol": "HTTP/1.1",
      "sourceIp": "127.0.0.1",
      "userAgent": "Custom User Agent String"
    },
    "requestId": "c6af9ac6-7b61-11e6-9a41-93e8deadbeef",
    "routeKey": "{{method}} /{{path}}",
    "stage": "{{stage}}",
    "time": "09/Apr/2015:12:34:56 +0000",
    "timeEpoch": 1428582896000
  },
  "body": "{{body}}",
  "isBase64Encoded": false
}
""".trimIndent()
    
    private fun getSQSEventTemplate(): String = """
{
  "Records": [
    {
      "messageId": "19dd0b57-b21e-4ac1-bd88-01bbb068cb78",
      "receiptHandle": "MessageReceiptHandle",
      "body": "{{messageBody}}",
      "attributes": {
        "ApproximateReceiveCount": "1",
        "SentTimestamp": "{{timestamp}}",
        "SenderId": "AIDAIENQZJOL4TEXAMPLE",
        "ApproximateFirstReceiveTimestamp": "{{timestamp}}"
      },
      "messageAttributes": {},
      "md5OfBody": "7b270e59b47ff90a553787216d55d91d",
      "eventSource": "aws:sqs",
      "eventSourceARN": "arn:aws:sqs:{{region}}:123456789012:{{queueName}}",
      "awsRegion": "{{region}}"
    }
  ]
}
""".trimIndent()
    
    private fun getSNSEventTemplate(): String = """
{
  "Records": [
    {
      "EventSource": "aws:sns",
      "EventVersion": "1.0",
      "EventSubscriptionArn": "arn:aws:sns:{{region}}:123456789012:{{topicName}}:{{subscriptionId}}",
      "Sns": {
        "Type": "Notification",
        "MessageId": "95df01b4-ee98-5cb9-9903-4c221d41eb5e",
        "TopicArn": "arn:aws:sns:{{region}}:123456789012:{{topicName}}",
        "Subject": "{{subject}}",
        "Message": "{{message}}",
        "Timestamp": "{{timestamp}}",
        "SignatureVersion": "1",
        "Signature": "EXAMPLE",
        "SigningCertUrl": "EXAMPLE",
        "UnsubscribeUrl": "EXAMPLE",
        "MessageAttributes": {}
      }
    }
  ]
}
""".trimIndent()
    
    private fun getDynamoDBEventTemplate(): String = """
{
  "Records": [
    {
      "eventID": "1",
      "eventVersion": "1.0",
      "dynamodb": {
        "Keys": {
          "Id": {
            "N": "101"
          }
        },
        "NewImage": {
          "Message": {
            "S": "{{message}}"
          },
          "Id": {
            "N": "101"
          }
        },
        "StreamViewType": "NEW_AND_OLD_IMAGES",
        "SequenceNumber": "111",
        "SizeBytes": 26
      },
      "awsRegion": "{{region}}",
      "eventName": "INSERT",
      "eventSourceARN": "arn:aws:dynamodb:{{region}}:123456789012:table/{{tableName}}/stream/2015-06-27T00:48:05.000",
      "eventSource": "aws:dynamodb"
    }
  ]
}
""".trimIndent()
    
    private fun getKinesisEventTemplate(): String = """
{
  "Records": [
    {
      "kinesis": {
        "kinesisSchemaVersion": "1.0",
        "partitionKey": "s1",
        "sequenceNumber": "49590338271490256608559692538361571095921575989136588898",
        "data": "{{base64Data}}",
        "approximateArrivalTimestamp": {{timestamp}}
      },
      "eventSource": "aws:kinesis",
      "eventVersion": "1.0",
      "eventID": "shardId-000000000006:49590338271490256608559692538361571095921575989136588898",
      "eventName": "aws:kinesis:record",
      "invokeIdentityArn": "arn:aws:iam::123456789012:role/lambda-role",
      "awsRegion": "{{region}}",
      "eventSourceARN": "arn:aws:kinesis:{{region}}:123456789012:stream/{{streamName}}"
    }
  ]
}
""".trimIndent()
    
    private fun getScheduledEventTemplate(): String = """
{
  "version": "0",
  "id": "53dc4d37-cffa-4f76-80c9-8b7d4a4d2dfb",
  "detail-type": "Scheduled Event",
  "source": "aws.events",
  "account": "123456789012",
  "time": "{{timestamp}}",
  "region": "{{region}}",
  "resources": [
    "arn:aws:events:{{region}}::rule/{{ruleName}}"
  ],
  "detail": {}
}
""".trimIndent()
    
    private fun getSimpleEventTemplate(): String = """
{
  "key1": "value1",
  "key2": "value2",
  "key3": "value3"
}
""".trimIndent()
}

/**
 * Enumeration of supported Lambda event types.
 */
enum class LambdaEventType {
    S3,
    APIGateway,
    APIGatewayV2,
    SQS,
    SNS,
    DynamoDB,
    Kinesis,
    Scheduled,
    Simple
}
