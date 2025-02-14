import requests
from bs4 import BeautifulSoup
import os
from concurrent.futures import ThreadPoolExecutor
import time
from urllib.parse import urljoin
import re
import json
import csv
from google.colab import files
import shutil
import threading
import glob

# Constants
BASE_URL = "https://papers.nips.cc"
DATASETS_BENCHMARKS_URL_2021 = "https://datasets-benchmarks-proceedings.neurips.cc"
OUTPUT_DIR = "/content/scraped-pdfs/"
METADATA_DIR = "/content/metadata/"
THREAD_COUNT = 50
MAX_RETRIES = 3
TIMEOUT = 60  # seconds
START_YEAR = 1987
END_YEAR = 2024

# Locks for thread-safe writing
json_lock = threading.Lock()
metadata_list_lock = threading.Lock()

# List to store all metadata
metadata_list = []

def sanitize_filename(filename):
    return re.sub(r'[\\/*?:"<>|]', '_', filename)

def download_pdf(session, pdf_url, filename):
    filename = sanitize_filename(filename)
    filepath = os.path.join(OUTPUT_DIR, f"{filename}.pdf")
    if os.path.exists(filepath):
        print(f"File exists: {filepath}")
        return
    try:
        with session.get(pdf_url, stream=True, timeout=TIMEOUT) as response:
            response.raise_for_status()
            with open(filepath, 'wb') as f:
                for chunk in response.iter_content(chunk_size=16384):
                    if chunk:
                        f.write(chunk)
        print(f"Downloaded: {filepath}")
    except Exception as e:
        print(f"Failed to download {pdf_url}: {e}")

def save_metadata_incremental_json(metadata, year):
    json_file = f"neurips_{year}.json"
    with json_lock:
        with open(json_file, "a", encoding="utf-8") as f:
            f.write(json.dumps(metadata, indent=4) + "\n")

def process_paper_2022_2023(session, paper_url, year, download_pdfs):
    for attempt in range(MAX_RETRIES):
        try:
            response = session.get(paper_url, timeout=TIMEOUT)
            soup = BeautifulSoup(response.text, 'html.parser')

            title = soup.title.text.strip().replace(" - NeurIPS", "") if soup.title else "Untitled"

            abstract_h4 = soup.find('h4', string=re.compile("Abstract", re.I))
            abstract = "No abstract available"
            if abstract_h4:
                abstract_p = abstract_h4.find_next('p')
                if abstract_p:
                    abstract = abstract_p.get_text(strip=True)

            if download_pdfs:
                pdf_link = soup.find('a', href=lambda href: href and href.endswith('Paper-Conference.pdf'))
                if not pdf_link:
                    print(f"No PDF link found: {paper_url}")
                    return
                pdf_url = urljoin(BASE_URL, pdf_link['href'])
                download_pdf(session, pdf_url, f"{year}_{title}")

            metadata = {
                "year": year,
                "title": title,
                "abstract": abstract
            }
            
            # Save individual JSON with year directory
            year_dir = os.path.join(METADATA_DIR, str(year))
            os.makedirs(year_dir, exist_ok=True)
            metadata_filename = sanitize_filename(f"{title}") + ".json"
            metadata_filepath = os.path.join(year_dir, metadata_filename)
            with open(metadata_filepath, 'w') as f:
                json.dump(metadata, f, indent=4)
            
            # Save to incremental JSON
            save_metadata_incremental_json(metadata, year)

            # Add metadata to the global list
            with metadata_list_lock:
                metadata_list.append(metadata)

            return
        except Exception as e:
            print(f"Attempt {attempt+1} failed for {paper_url}: {e}")
            time.sleep(2 ** attempt)
    print(f"Giving up: {paper_url}")

def process_paper(session, paper_url, year, download_pdfs):
    for attempt in range(MAX_RETRIES):
        try:
            response = session.get(paper_url, timeout=TIMEOUT)
            soup = BeautifulSoup(response.text, 'html.parser')

            title_tag = soup.find('h4')
            title = title_tag.get_text(strip=True) if title_tag else "Untitled"

            abstract_h4 = soup.find('h4', string=re.compile("Abstract", re.I))
            abstract = "No abstract available"
            if abstract_h4:
                abstract_p = abstract_h4.find_next('p')
                if abstract_p and not abstract_p.get_text(strip=True):
                    abstract_p = abstract_p.find_next('p')
                if abstract_p:
                    abstract = abstract_p.get_text(strip=True)

            if download_pdfs:
                pdf_link = soup.find('a', href=lambda href: href and "Paper.pdf" in href)
                if not pdf_link:
                    print(f"No PDF link: {paper_url}")
                    return
                pdf_url = urljoin(BASE_URL, pdf_link['href'])
                download_pdf(session, pdf_url, f"{year}_{title}")

            metadata = {
                "year": year,
                "title": title,
                "abstract": abstract
            }
            
            # Save individual JSON with year directory
            year_dir = os.path.join(METADATA_DIR, str(year))
            os.makedirs(year_dir, exist_ok=True)
            metadata_filename = sanitize_filename(f"{title}") + ".json"
            metadata_filepath = os.path.join(year_dir, metadata_filename)
            with open(metadata_filepath, 'w') as f:
                json.dump(metadata, f, indent=4)
            
            # Save to incremental JSON
            save_metadata_incremental_json(metadata, year)

            # Add metadata to the global list
            with metadata_list_lock:
                metadata_list.append(metadata)

            return
        except Exception as e:
            print(f"Attempt {attempt+1} failed for {paper_url}: {e}")
            time.sleep(2 ** attempt)
    print(f"Giving up: {paper_url}")

def process_dataset_benchmark_papers(session, url, download_pdfs):
    try:
        response = session.get(url)
        soup = BeautifulSoup(response.text, 'html.parser')
        paper_links = soup.select('a[href$="Abstract.html"]')
        for paper_link in paper_links:
            paper_url = urljoin(url, paper_link['href'])
            process_paper(session, paper_url, 2021, download_pdfs)
    except Exception as e:
        print(f"Error processing datasets and benchmarks papers: {e}")

def get_user_years():
    while True:
        try:
            user_input = input(f"Enter the year range (e.g., 2019-2024) between {START_YEAR} and {END_YEAR}: ")
            start_year, end_year = map(int, user_input.split('-'))
            if start_year < START_YEAR or end_year > END_YEAR or start_year > end_year:
                print(f"Error: Please enter a valid range between {START_YEAR} and {END_YEAR}.")
                continue
            return list(range(start_year, end_year + 1))
        except ValueError:
            print("Error: Please enter a valid range (e.g., 2019-2024).")

def get_download_option():
    while True:
        user_input = input("Do you want to download both PDFs and metadata (1) or only metadata (2)? Enter 1 or 2: ")
        if user_input in ['1', '2']:
            return user_input == '1'
        print("Error: Please enter 1 or 2.")

def write_metadata_to_csv(years):
    csv_filename = f"neurips_{years[0]}-{years[-1]}.csv"
    headers = ["year", "title", "abstract"]
    
    with open(csv_filename, 'w', newline='', encoding='utf-8') as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=headers)
        writer.writeheader()
        for metadata in metadata_list:
            writer.writerow(metadata)
    
    print(f"Metadata saved to {csv_filename}")

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    os.makedirs(METADATA_DIR, exist_ok=True)

    download_pdfs = get_download_option()
    years_to_scrape = get_user_years()
    print(f"Scraping years: {years_to_scrape}")

    with requests.Session() as session:
        response = session.get(BASE_URL)
        soup = BeautifulSoup(response.text, 'html.parser')
        year_links = soup.select('a[href^="/paper_files/paper/"]')

        with ThreadPoolExecutor(max_workers=THREAD_COUNT) as executor:
            for year_link in year_links:
                year_url = urljoin(BASE_URL, year_link['href'])
                try:
                    year = int(year_url.split('/')[-1])
                except (ValueError, IndexError):
                    print(f"Skipping invalid year URL: {year_url}")
                    continue

                if year in years_to_scrape:
                    print(f"Processing year: {year}")
                    try:
                        year_response = session.get(year_url)
                        year_soup = BeautifulSoup(year_response.text, 'html.parser')
                        
                        if year > 2021:
                            paper_links = year_soup.select('a[href$="Abstract-Conference.html"]')
                        else:
                            paper_links = year_soup.select('a[href$="Abstract.html"]')

                        for paper_link in paper_links:
                            paper_url = urljoin(year_url, paper_link['href'])
                            if year > 2021:
                                executor.submit(process_paper_2022_2023, session, paper_url, year, download_pdfs)
                            else:
                                executor.submit(process_paper, session, paper_url, year, download_pdfs)
                    except Exception as e:
                        print(f"Year {year_url} error: {e}")
                else:
                    print(f"Skipping year {year} (not selected)")

            if 2021 in years_to_scrape:
                executor.submit(process_dataset_benchmark_papers, session, DATASETS_BENCHMARKS_URL_2021, download_pdfs)

    # Write all metadata to a single CSV file
    write_metadata_to_csv(years_to_scrape)

if __name__ == "__main__":
    main()
    
    # Create final output package
    FINAL_OUTPUT_DIR = "/content/final_output"
    os.makedirs(FINAL_OUTPUT_DIR, exist_ok=True)
    
    # Copy all year-specific CSV/JSON files
    for f in glob.glob("/content/neurips_*.*"):
        if f.endswith(('.csv', '.json')):
            shutil.copy(f, FINAL_OUTPUT_DIR)
    
    # Copy metadata directory
    shutil.copytree(METADATA_DIR, os.path.join(FINAL_OUTPUT_DIR, "metadata"))
    
    # Create zip archive
    shutil.make_archive("/content/neurips_data", 'zip', FINAL_OUTPUT_DIR)
    
    # Download results
    files.download("/content/neurips_data.zip")
    
    # Cleanup
    shutil.rmtree(FINAL_OUTPUT_DIR)