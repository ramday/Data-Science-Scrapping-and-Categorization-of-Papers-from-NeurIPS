README
Overview
This repository contains two scrapers for downloading NeurIPS conference papers and their metadata:

A Java scraper for papers from 1987 and 2023.

A Python scraper for papers from 1987 to 2023.

Prerequisites
Java Scraper
Java Development Kit (JDK) installed. You can download it from here.

Apache HttpClient library.

Jsoup library.

Python Scraper
Python 3 installed. You can download it from here.

Required Python libraries: requests, beautifulsoup4, urllib3, re, json, csv, shutil, threading, concurrent.futures, google.colab.

Instructions
Java Scraper
Set Up Libraries:

Download the Apache HttpClient library and Jsoup library.

Add the JAR files to your project’s classpath.

Compile and Run:

Save the Java scraper code to a file named PDFScraper.java.

Open a terminal/command prompt.

Navigate to the directory containing PDFScraper.java.

Compile the Java code:

sh
javac -cp ".:/path/to/httpclient.jar:/path/to/jsoup.jar" PDFScraper.java
Run the Java code:

sh
java -cp ".:/path/to/httpclient.jar:/path/to/jsoup.jar" PDFScraper
Python Scraper
Set Up Environment:

Ensure you have Python 3 installed.

Install the required libraries:

sh
pip install requests beautifulsoup4 urllib3 re json csv google.colab
Run the Script:

Save the Python scraper code to a file named scraper.py.

Open a terminal/command prompt.

Navigate to the directory containing scraper.py.

Run the Python script:

sh
python scraper.py
Running the Scripts
Java Scraper
The Java scraper will download NeurIPS papers from 2022 and 2023, and save the PDFs and metadata to the specified directories.

Python Scraper
The Python scraper allows you to specify up to 5 years between 1987 and 2023 to scrape NeurIPS papers. It will download the PDFs and save the metadata in both JSON and CSV formats. After completing the downloads, the script will compress the directories and prompt you to download the archives.

Cleaning Up
If you want to delete all scraped PDFs and metadata files, use the following Python script:

python
import os
import shutil

# Constants
OUTPUT_DIR = "/content/scraped-pdfs/"
METADATA_DIR = "/content/metadata/"

def delete_files_in_directory(directory):
    if os.path.exists(directory):
        for filename in os.listdir(directory):
            file_path = os.path.join(directory, filename)
            try:
                if os.path.isfile(file_path) or os.path.islink(file_path):
                    os.unlink(file_path)
                elif os.path.isdir(file_path):
                    shutil.rmtree(file_path)
            except Exception as e:
                print(f"Failed to delete {file_path}: {e}")
        print(f"All files in {directory} have been deleted.")
    else:
        print(f"Directory {directory} does not exist.")

def delete_zip_files(directory):
    if os.path.exists(directory):
        for filename in os.listdir(directory):
            if filename.endswith(".zip"):
                file_path = os.path.join(directory, filename)
                try:
                    os.unlink(file_path)
                except Exception as e:
                    print(f"Failed to delete ZIP file {file_path}: {e}")
        print(f"All ZIP files in {directory} have been deleted.")
    else:
        print(f"Directory {directory} does not exist.")

def main():
    delete_files_in_directory(OUTPUT_DIR)
    delete_files_in_directory(METADATA_DIR)
    delete_zip_files(OUTPUT_DIR)
    delete_zip_files(METADATA_DIR)

if __name__ == "__main__":
    main()