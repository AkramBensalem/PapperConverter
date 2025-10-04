from pathlib import Path
from mistralai import Mistral
import datauri
import json
import os
from dotenv import load_dotenv

load_dotenv()

client = Mistral(api_key=os.getenv("MISTRAL_API_KEY"))

input_folder = Path("./assets")
output_folder = Path("./output")
output_folder.mkdir(exist_ok=True)

print("Starting OCR...")

for pdf_file in input_folder.glob("*.pdf"):
    print(f"Processing {pdf_file.name}...")

    uploaded = client.files.upload(
        file={"file_name": pdf_file.name, "content": pdf_file.read_bytes()},
        purpose="ocr",
    )

    print(f"Uploaded {pdf_file.name} -> {uploaded.id}")
    url = client.files.get_signed_url(file_id=uploaded.id).url

    resp = client.ocr.process(
        model="mistral-ocr-latest",
        document={"type": "document_url", "document_url": url},
        include_image_base64=True,
    )

    pdf_output = output_folder / pdf_file.stem
    pdf_output.mkdir(exist_ok=True)

    # Sauvegarde JSON complet
    with open(pdf_output / f"{pdf_file.stem}.json", "w", encoding="utf-8") as f:
        json.dump(resp.model_dump(), f, ensure_ascii=False, indent=2)

    # Sauvegarde Markdown et images
    combined_md = []
    for i, page in enumerate(resp.pages, start=1):
        combined_md.append(f"{page.markdown}\n")
        for img in page.images:
            data = datauri.parse(img.image_base64).data
            with open(pdf_output / f"{img.id}", "wb") as imgf:
                imgf.write(data)
    (pdf_output / f"{pdf_file.stem}.md").write_text("".join(combined_md), encoding="utf-8")

    print(f"✅ {pdf_file.name} traité -> {pdf_output}")
