using System;
using System.Diagnostics;
using System.Threading;

/// <summary>
/// .NET Startup Hook that pauses the process until a debugger attaches.
/// Used by the AWS Lambda Test Tool plugin to ensure breakpoints work
/// when debugging Lambda functions in Rider.
///
/// Activated via DOTNET_STARTUP_HOOKS environment variable.
/// Only pauses when LAMBDA_DEBUG_WAIT=1 is set.
/// </summary>
internal class StartupHook
{
    internal static void Initialize()
    {
        if (Environment.GetEnvironmentVariable("LAMBDA_DEBUG_WAIT") != "1")
            return;

        var pid = Process.GetCurrentProcess().Id;
        Console.WriteLine($"[Lambda Debug] Process {pid} waiting for debugger to attach...");

        // Wait up to 30 seconds for a debugger to attach
        var timeout = TimeSpan.FromSeconds(30);
        var started = DateTime.UtcNow;

        while (!Debugger.IsAttached)
        {
            if (DateTime.UtcNow - started > timeout)
            {
                Console.WriteLine("[Lambda Debug] Timeout waiting for debugger. Continuing without debugger.");
                return;
            }
            Thread.Sleep(100);
        }

        Console.WriteLine("[Lambda Debug] Debugger attached. Continuing execution.");
    }
}
