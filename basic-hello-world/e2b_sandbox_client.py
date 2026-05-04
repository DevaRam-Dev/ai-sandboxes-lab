#!/usr/bin/env python3
"""
E2B sandbox runner — bridges Spring Boot to E2B via official Python SDK.

Usage: python3 e2b_sandbox_client.py <code-file.py> <output-chart.png>

Reads Python code from <code-file.py>, runs it inside an E2B sandbox,
saves the resulting chart.png to <output-chart.png>.

Reads E2B_API_KEY from environment variable.
"""
import sys
import os
import base64
from e2b_code_interpreter import Sandbox


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <code-file> <output-png>", file=sys.stderr)
        sys.exit(1)

    code_file = sys.argv[1]
    output_png = sys.argv[2]

    # Read AI-generated Python code from file
    with open(code_file, "r") as f:
        code = f.read()

    print(f"[e2b_runner] Code length: {len(code)} chars")
    print(f"[e2b_runner] Output target: {output_png}")
    print(f"[e2b_runner] Creating E2B sandbox...")

    # E2B SDK reads E2B_API_KEY from env var automatically
    with Sandbox.create() as sandbox:
        print(f"[e2b_runner] Sandbox ready, executing code...")

        # Execute the Python code inside the sandbox
        execution = sandbox.run_code(code)

        # Check for runtime errors inside the sandbox
        if execution.error:
            print(f"[e2b_runner] Sandbox runtime error: {execution.error}", file=sys.stderr)
            sys.exit(2)

        print(f"[e2b_runner] Execution complete. Results: {len(execution.results)} items")

        # Look for a chart in execution results
        for idx, result in enumerate(execution.results):
            print(f"[e2b_runner] Result #{idx}: type={type(result).__name__}, has_png={hasattr(result, 'png') and result.png is not None}")
            if hasattr(result, "png") and result.png:
                # PNG comes back as base64-encoded string
                png_bytes = base64.b64decode(result.png)
                with open(output_png, "wb") as f:
                    f.write(png_bytes)
                print(f"[e2b_runner] Chart saved: {output_png} ({len(png_bytes)} bytes)")
                return

        # No chart found
        print("[e2b_runner] ERROR: No chart found in execution results", file=sys.stderr)
        print(f"[e2b_runner] stdout from sandbox: {execution.logs.stdout if execution.logs else 'N/A'}", file=sys.stderr)
        sys.exit(3)


if __name__ == "__main__":
    main()
