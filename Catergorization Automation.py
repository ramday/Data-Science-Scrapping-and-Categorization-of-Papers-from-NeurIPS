# Install necessary libraries
 !pip install torch transformers datasets google-colab

# Step 2: Import libraries
import os
import json
import csv
import time
import signal
import sys
from google.colab import files
from transformers import pipeline
from datasets import Dataset, load_dataset
from typing import List, Dict
import torch
from torch.amp import autocast, GradScaler

# Step 4: Define categories and configuration
CATEGORIES = [
    "Deep Learning",
    "Computer Vision",
    "Reinforcement Learning",
    "Natural Language Processing",
    "Optimization"
]

RATE_LIMIT_DELAY = 1  # Seconds between API calls

# Step 5: Define the PaperAnnotator class using Hugging Face with mixed precision
class PaperAnnotator:
    def __init__(self):
        self.classifier = pipeline('zero-shot-classification', model='facebook/bart-large-mnli', device=0)  # Use GPU
        self.scaler = GradScaler('cuda')

    def classify_papers(self, papers: List[Dict[str, str]]) -> List[str]:
        texts = [f"Title: {paper['title']}\nAbstract: {paper['abstract']}" for paper in papers]
        with autocast('cuda'):
            results = self.classifier(texts, CATEGORIES, batch_size=16)  # Process in batches of 16
        return [result['labels'][0] for result in results]

# Step 6: Define functions to process CSV and JSON files
def process_csv_file(file_path: str, annotator: PaperAnnotator):
    updated_rows = []
    
    with open(file_path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames + ['category'] if 'category' not in reader.fieldnames else reader.fieldnames
        papers = [row for row in reader]
    
    try:
        categories = annotator.classify_papers(papers)
        for row, category in zip(papers, categories):
            try:
                row['category'] = category
                updated_rows.append(row)
            except Exception as e:
                print(f"Error processing row {row}: {e}")
                continue
    except KeyboardInterrupt:
        print("Interrupted! Saving progress so far...")
        with open(file_path, 'w', newline='', encoding='utf-8') as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for row in updated_rows:
                writer.writerow({key: row.get(key, '') for key in fieldnames})
        return updated_rows

    with open(file_path, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in updated_rows:
            writer.writerow({key: row.get(key, '') for key in fieldnames})

def process_json_file(file_path: str, annotator: PaperAnnotator):
    with open(file_path, 'r', encoding='utf-8') as f:
        papers = json.load(f)
    
    try:
        categories = annotator.classify_papers(papers)
        for paper, category in zip(papers, categories):
            try:
                paper['category'] = category
            except Exception as e:
                print(f"Error processing paper {paper}: {e}")
                continue
    except KeyboardInterrupt:
        print("Interrupted! Saving progress so far...")
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(papers, f, indent=4)
        return papers
    
    with open(file_path, 'w', encoding='utf-8') as f:
        json.dump(papers, f, indent=4)

# Step 7: Main function to process all files
def main():
    annotator = PaperAnnotator()
    
    # Ask user to upload files
    print("Please upload your CSV and/or JSON files.")
    uploaded = files.upload()
    
    # Process uploaded CSV files
    for filename in uploaded.keys():
        if filename.endswith('.csv'):
            print(f"Processing {filename}")
            process_csv_file(filename, annotator)
    
    # Process uploaded JSON files
    for filename in uploaded.keys():
        if filename.endswith('.json'):
            print(f"Processing {filename}")
            process_json_file(filename, annotator)
    
    # Zip and download the annotated files
    os.system('zip -r annotated_data.zip ./*.csv ./*.json')  # Zip only the CSV and JSON files
    files.download("annotated_data.zip")

# Handle interruption to save progress
def signal_handler(sig, frame):
    print("You pressed Ctrl+C! Saving progress...")
    os.system('zip -r annotated_data.zip ./*.csv ./*.json')  # Zip only the CSV and JSON files
    files.download("annotated_data.zip")
    sys.exit(0)

signal.signal(signal.SIGINT, signal_handler)

# Step 8: Run the main function
if __name__ == "__main__":
    main()
