"""Validate the packaged streaming model with the upstream Chinese and English WAV fixtures."""
from pathlib import Path
import json
import time
import wave
import numpy as np
import sherpa_onnx

root = Path(__file__).resolve().parents[2]
model = root / 'app/src/main/assets/offline-voice'
recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
    tokens=str(model/'tokens.txt'),
    encoder=str(model/'encoder.int8.onnx'),
    decoder=str(model/'decoder.onnx'),
    joiner=str(model/'joiner.int8.onnx'),
    num_threads=2, sample_rate=16000, feature_dim=80,
    decoding_method='greedy_search', enable_endpoint_detection=False,
    model_type='zipformer', provider='cpu')
results = []
for index in range(4):
    path = root / '.local-build' / f'test-{index}.wav'
    with wave.open(str(path), 'rb') as f:
        assert f.getnchannels() == 1 and f.getsampwidth() == 2
        rate = f.getframerate()
        samples = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    stream = recognizer.create_stream()
    start = time.monotonic()
    for offset in range(0, len(samples), 320):
        stream.accept_waveform(rate, samples[offset:offset+320])
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
    stream.accept_waveform(rate, np.zeros(8000, dtype=np.float32))
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    text = recognizer.get_result(stream)
    assert text.strip(), f'Empty result for {path.name}'
    results.append({'fixture': path.name, 'text': text, 'audio_seconds': len(samples)/rate,
                    'processing_seconds': round(time.monotonic()-start, 3)})
assert any(any('\u4e00' <= c <= '\u9fff' for c in x['text']) for x in results)
assert any(any('a' <= c.lower() <= 'z' for c in x['text']) for x in results)
output = root / '.local-build/model-validation.json'
output.write_text(json.dumps({'sherpa_version': sherpa_onnx.__version__, 'results': results}, ensure_ascii=False, indent=2), encoding='utf-8')
print(output.read_text(), flush=True)
