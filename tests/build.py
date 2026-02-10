#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Batch convert RISC-V ELF files to binary (.bin) and hexadecimal (.hex) files
Dependencies: riscv32-unknown-elf-objcopy, od, sed, file commands (must be pre-installed)
"""
import os
import subprocess
import sys

# ===================== Configuration (Modify as needed) =====================
# Target directory: Path to store RISC-V ELF test files
TESTS_DIR = "/home/xiaoyu/workspace/riscv-mini/tests"
# Path to RISC-V objcopy command (use full path if not in system PATH)
OBJCOPY_CMD = "riscv32-unknown-elf-objcopy"
# Filter ELF files by prefix: Match files starting with "rv32" (adjust as needed)
ELF_FILE_PREFIX = "rv32"

# ===================== Core Functions =====================
def run_command(cmd, description):
    """
    Execute system command with error handling
    Args:
        cmd (str): System command to execute
        description (str): Human-readable description of the command
    Returns:
        bool: True if command succeeds, False otherwise
    """
    try:
        print(f"[INFO] Executing: {description}")
        result = subprocess.run(
            cmd,
            shell=True,  # Allow pipe commands (od | sed)
            check=True,  # Raise exception if command returns non-zero exit code
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True  # Return output as string (not bytes)
        )
        return True
    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Failed to execute {description}:")
        print(f"        Command: {e.cmd}")
        print(f"        Error output: {e.stderr}")
        return False
    except Exception as e:
        print(f"[ERROR] Unexpected error when executing {description}: {str(e)}")
        return False

def is_elf_file(file_path):
    """
    Check if a file is a valid RISC-V ELF binary using the 'file' command
    Args:
        file_path (str): Full path to the file to check
    Returns:
        bool: True if file is RISC-V ELF, False otherwise
    """
    try:
        # Run 'file' command to get file type info
        result = subprocess.run(
            f"file {file_path}",
            shell=True,
            capture_output=True,
            text=True
        )
        # Check if output contains "ELF" and "RISCV" (case-insensitive)
        return "ELF" in result.stdout and "RISCV" in result.stdout.upper()
    except Exception as e:
        print(f"[WARN] Failed to check file format for {file_path}: {str(e)}")
        return False

def process_elf_file(elf_path):
    """
    Process a single ELF file to generate corresponding .bin and .hex files
    Args:
        elf_path (str): Full path to the input ELF file
    Returns:
        bool: True if conversion succeeds, False otherwise
    """
    # Extract filename without directory path
    elf_filename = os.path.basename(elf_path)
    # Construct paths for output .bin and .hex files
    bin_path = os.path.join(TESTS_DIR, f"{elf_filename}.bin")
    hex_path = os.path.join(TESTS_DIR, f"{elf_filename}.hex")

    # Step 1: Run objcopy to generate binary (.bin) file
    objcopy_cmd = f"{OBJCOPY_CMD} -O binary {elf_path} {bin_path}"
    if not run_command(objcopy_cmd, f"Generate {elf_filename}.bin"):
        return False  # Skip hex generation if bin creation fails

    # Step 2: Run od + sed to generate hex (.hex) file (remove leading spaces)
    od_cmd = (
        f"od -An -tx4 -w4 -v --endian=little {bin_path} | sed 's/^ //' > {hex_path}"
    )
    if not run_command(od_cmd, f"Generate {elf_filename}.hex"):
        # Clean up invalid bin file if hex generation fails (optional)
        if os.path.exists(bin_path):
            os.remove(bin_path)
            print(f"[WARN] Deleted invalid {elf_filename}.bin")
        return False

    print(f"[SUCCESS] Process completed: {elf_filename} → {elf_filename}.bin + {elf_filename}.hex\n")
    return True

def main():
    """
    Main function: Traverse target directory and batch process valid ELF files
    """
    # Validate target directory exists
    if not os.path.isdir(TESTS_DIR):
        print(f"[ERROR] Target directory does not exist: {TESTS_DIR}")
        sys.exit(1)

    # Traverse all files in the target directory
    processed_count = 0
    failed_count = 0
    skipped_count = 0

    for filename in os.listdir(TESTS_DIR):
        file_path = os.path.join(TESTS_DIR, filename)
        
        # Skip directories (only process regular files)
        if not os.path.isfile(file_path):
            skipped_count += 1
            continue

        # Skip non-rv32 files (optional but reduces unnecessary checks)
        if not filename.startswith(ELF_FILE_PREFIX):
            skipped_count += 1
            continue

        # Skip already generated .bin/.hex files
        if filename.endswith((".bin", ".hex", ".dump")):
            skipped_count += 1
            continue

        # Critical check: Only process valid RISC-V ELF files
        if not is_elf_file(file_path):
            print(f"[INFO] Skipping non-ELF file: {filename} (format not recognized)")
            skipped_count += 1
            continue

        # Process valid ELF file
        if process_elf_file(file_path):
            processed_count += 1
        else:
            failed_count += 1

    # Print processing summary
    print("="*60)
    print(f"Processing Complete! Full Summary:")
    print(f"  Successfully processed: {processed_count} files")
    print(f"  Failed to process:      {failed_count} files")
    print(f"  Skipped (non-ELF/bin/hex): {skipped_count} files")
    print("="*60)

if __name__ == "__main__":
    main()