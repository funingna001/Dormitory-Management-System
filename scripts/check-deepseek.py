"""Check DeepSeek connectivity with a synthetic question, without exposing credentials."""
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
from urllib.parse import urlparse

config = {}
env_file = Path(__file__).resolve().parent.parent / '.env'
if env_file.exists():
    for line in env_file.read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if line and not line.startswith(('#', '!')) and '=' in line:
            name, value = line.split('=', 1)
            config[name.strip()] = value.strip()


def setting(name, default=''):
    return os.environ.get(name, config.get(name, default))


key = setting('DEEPSEEK_API_KEY')
url = setting('DEEPSEEK_API_URL', 'https://api.deepseek.com/v1/chat/completions')
model = setting('DEEPSEEK_MODEL', 'deepseek-flash')
endpoint = urlparse(url)
try:
    official = (endpoint.scheme == 'https' and endpoint.hostname == 'api.deepseek.com'
                and endpoint.port in (None, 443)
                and endpoint.path in ('/chat/completions', '/v1/chat/completions')
                and not endpoint.username and not endpoint.password
                and not endpoint.query and not endpoint.fragment)
except ValueError:
    official = False
print(json.dumps({'key_present': bool(key), 'official_endpoint': official, 'model_present': bool(model)}))
if not key or not official or not model:
    raise SystemExit('Configuration check failed; no request sent.')
if key.startswith(('"', "'")) or model.startswith(('"', "'")):
    raise SystemExit('Remove surrounding quotes from .env property values; no request sent.')

payload = {
    'model': model,
    'messages': [
        {'role': 'system', 'content': 'Classify a dormitory statistics question. Return JSON only with metric: occupancy, repairs, or summary. Never invent statistics.'},
        {'role': 'user', 'content': 'What is the current occupancy rate?'},
    ],
    'max_tokens': 150,
    'response_format': {'type': 'json_object'},
    'thinking': {'type': 'disabled'},
}
request = urllib.request.Request(url, json.dumps(payload).encode('utf-8'),
                                 headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(request, timeout=35) as response:
        data = json.load(response)
        choice = data.get('choices', [{}])[0]
        content = choice.get('message', {}).get('content', '')
        try:
            metric = json.loads(content).get('metric')
        except (ValueError, AttributeError):
            metric = None
        valid = metric == 'occupancy' and choice.get('finish_reason') == 'stop'
        print(json.dumps({'http_status': response.status, 'intent_valid': valid}))
        if not valid:
            raise SystemExit(1)
except urllib.error.HTTPError as failure:
    print(json.dumps({'http_status': failure.code, 'request_success': False}))
    raise SystemExit(1)
except Exception as failure:
    # Do not print error bodies, request headers or credentials.
    reason = getattr(failure, 'reason', failure)
    print(json.dumps({'request_success': False, 'error_type': type(reason).__name__}))
    raise SystemExit(2)
