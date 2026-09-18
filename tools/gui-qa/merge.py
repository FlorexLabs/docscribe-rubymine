#!/usr/bin/env python3
# Merge GUI-driver results into qa-results-auto.json by itemKey().
# Usage: merge.py <TAG> <STATUS> <NOTE>   (TAG like 3b1; STATUS ok/fail)
# Section+ordinal mapping: file cases/3a2.sh -> section 3A item #2 etc.
import json, sys

TAG, STATUS, NOTE = sys.argv[1], sys.argv[2], sys.argv[3]
SEC = TAG[:2].upper()          # 3b -> 3B
ORD = int(TAG[2:])             # 3b1 -> 1

P = '/Users/pearl/projects/docscribe/qa-results-auto.json'
d = json.load(open(P))
items = d['items']
sec_idx = [i for i, it in enumerate(items) if it['key'].startswith(SEC + '.')]
assert sec_idx, f'no section {SEC}'
assert 1 <= ORD <= len(sec_idx), f'{TAG}: section {SEC} has {len(sec_idx)} items'
gi = sec_idx[ORD - 1]
it = items[gi]
it['status'] = STATUS
it['note'] = NOTE
json.dump(d, open(P, 'w'), ensure_ascii=False, indent=1)
print(f'merged {TAG} -> [{gi}] {STATUS}: {it["key"][:70]}')
