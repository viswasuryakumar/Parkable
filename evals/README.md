# Parkable evals

This folder contains a lightweight benchmark skeleton for evaluating parking-sign extraction and verdict quality.

## Dataset format

The schema lives in [eval_dataset.schema.json](eval_dataset.schema.json) and the sample data lives in [eval_dataset.json](eval_dataset.json).

Each entry includes:
- a photo reference path
- the ground-truth verdict captured at a fixed instant
- a normalized rule JSON payload
- a failure tag used for review triage

## Labeling workflow

1. Review the sign photo and the expected rule JSON.
2. Record the verdict at the fixed instant from the dataset.
3. Tag any failure with one of: `ocr`, `arrow`, `schema`, or `reasoning`.
4. Store the result in a separate review file when you expand the dataset beyond the starter sample.
