#!/usr/bin/env python3
"""
PDF to Markdown converter using MarkItDown library.
Handles dependency installation and graceful error reporting.
"""

import sys
import subprocess
from pathlib import Path


def install_markitdown():
    """Install MarkItDown library if not available."""
    try:
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install", "markitdown[all]"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE
        )
        return True
    except subprocess.CalledProcessError as e:
        print(f"ERROR:Failed to install markitdown: {e.stderr.decode()}", file=sys.stderr)
        return False


def convert_pdf_to_markdown(pdf_path: str) -> None:
    """
    Convert a PDF file to markdown format.

    Args:
        pdf_path: Path to the PDF file to convert
    """
    # Validate input file
    if not pdf_path or pdf_path == "$pdfPath":
        print("ERROR:No PDF path provided", file=sys.stderr)
        sys.exit(1)

    pdf_file = Path(pdf_path)
    if not pdf_file.exists():
        print(f"ERROR:PDF file not found: {pdf_path}", file=sys.stderr)
        sys.exit(1)

    if not pdf_file.is_file():
        print(f"ERROR:Path is not a file: {pdf_path}", file=sys.stderr)
        sys.exit(1)

    # Import or install MarkItDown
    try:
        from markitdown import MarkItDown
    except ImportError:
        print("INFO:MarkItDown not found, attempting installation...", file=sys.stderr)
        if not install_markitdown():
            print("ERROR:MarkItDown library not found and installation failed. "
                  "Try: pip install markitdown[all]", file=sys.stderr)
            sys.exit(1)

        try:
            from markitdown import MarkItDown
        except ImportError:
            print("ERROR:Failed to import MarkItDown after installation", file=sys.stderr)
            sys.exit(1)

    # Convert PDF to markdown
    try:
        md = MarkItDown()
        result = md.convert(str(pdf_file))

        if result and hasattr(result, 'text_content') and result.text_content:
            print(f"SUCCESS:{result.text_content}")
        else:
            print("ERROR:Conversion produced empty result", file=sys.stderr)
            sys.exit(1)

    except Exception as e:
        print(f"ERROR:Conversion failed: {type(e).__name__}: {str(e)}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    # Get PDF path from command line or placeholder
    pdf_path = sys.argv[1] if len(sys.argv) > 1 else "$pdfPath"
    convert_pdf_to_markdown(pdf_path)