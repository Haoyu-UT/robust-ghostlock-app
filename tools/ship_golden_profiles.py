#!/usr/bin/env python3
"""Ship every golden profile from the test-kernels store into the app bundle.

Rule (CLAUDE.md): every store entry that has an option superset and at least
one feasible route is shipped as a bundled profile. Entries with
``corpus = btf-only | codegen | excluded``, entries without a superset, entries
without a feasible route, and an entry whose ``devices[]`` mixes null and
non-null physical pairs (a single HOCON map cannot carry both) are exempt.

The bundle asset is the CLI's default anchored cut (``--format conf --route
<best> --anchor --device <primary>``) rendered from the store Image with that
device's sidecars; the physical maps are then written from the superset's
``devices[]`` entries so every device in the set resolves. The profile's
``pipeline`` line is the superset's derived ``pipeline_order`` (highest
precedence first), overridable per entry by ``pipeline = ...`` in ``meta.txt``.
The route is chosen by the measured order (2026-11-14 A/B): ``select_stack``
(at the ceiling on every rig), ``tcp_zerocopy`` (18/20 on 6.1), the compact
``multicast_waiter`` (14/15 with the drain fix), then ``sigreturn`` (6/10) as
the last resort. A 5.10 pre-136 image whose 64-bit select is infeasible reports
the AArch32 compat ``select_stack`` as its own verdict (the carrier's geometry
is the extractor's), so the order sees the route that actually runs. New assets
carry a SHIPPED GOLDEN header and are never overwritten by a later run.

Usage:
    tools/ship_golden_profiles.py --check    # report unbundled goldens, exit 1
    tools/ship_golden_profiles.py --apply    # render + index the missing ones

Paths default to this checkout; override with --store/--assets/--extract.
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import migrate_profile_shape

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(os.path.dirname(APP))  # <repo>/external/ghostlock-app -> <repo>
DEFAULT_STORE = os.path.join(ROOT, "external", "test-kernels")
DEFAULT_ASSETS = os.path.join(APP, "app", "src", "main", "assets", "kernel_profiles")
DEFAULT_EXTRACT = os.path.join(APP, "build", "extract", "release", "ghostlock-extract")

SKIP_CORPUS = {"btf-only", "codegen", "excluded"}
# The measured order (2026-11-14 A/B): select > tcp > multicast > sigreturn.
ROUTE_PRIORITY = ["select_stack", "tcp_zerocopy", "multicast_waiter", "sigreturn"]
# The AArch32 compat select carrier's route block and geometry are the
# extractor's own now (analysis::apply_compat_select + report::
# conf_compat_route_geometry): a 5.10 pre-136 image whose 64-bit select is
# infeasible reports select_stack feasible with the measured family geometry,
# and the tool just renders the chosen route.
# Same order as the Rust store harness (tests/common/mod.rs sidecar_args).
SIDECARS = [
    ("vendor_boot.img", "--vendor-boot"),
    ("xbl_config.img", "--xbl-config"),
    ("xbl_config.elf", "--xbl-config"),
    ("uefi.img", "--uefi"),
    ("dtbo.img", "--dtbo"),
]


class NotShippable(Exception):
    pass


def parse_meta(directory):
    fields = {}
    for line in open(os.path.join(directory, "meta.txt"), encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        fields.setdefault(key.strip(), value.strip())
    return fields


def split_devices(value):
    return [part.strip().strip('"') for part in value.split(",") if part.strip()]


def entry_devices(meta, directory):
    devices = split_devices(meta.get("devices", ""))
    if not devices:
        primary = meta.get("device", "").strip().strip('"')
        if primary:
            devices = [primary]
    if not devices:
        devices = sorted(
            os.path.basename(path)
            for path in glob.glob(os.path.join(directory, "devices", "*"))
            if os.path.isdir(path)
        )
    return devices


def entry_image(meta, directory):
    image = meta.get("image", "").strip().strip('"')
    if image:
        path = os.path.join(directory, image)
        if os.path.isfile(path):
            return path
    images = sorted(glob.glob(os.path.join(directory, "Image-*")))
    if len(images) == 1:
        return images[0]
    raise NotShippable("no Image-* file")


def read_options(directory):
    superset = None
    for path in sorted(glob.glob(os.path.join(directory, "options-*.json"))):
        superset = path
    if superset is None:
        raise NotShippable("no option superset")
    with open(superset, encoding="utf-8") as handle:
        return json.load(handle)


def sidecar_args(directory, device):
    sidecar_dir = os.path.join(directory, "devices", device)
    if not os.path.isdir(sidecar_dir):
        sidecar_dir = directory
    args = []
    for name, flag in SIDECARS:
        path = os.path.join(sidecar_dir, name)
        if os.path.isfile(path):
            args += [flag, path]
    return args


def choose_route(options):
    feasible = [
        route.get("route")
        for route in options.get("routes", [])
        if route.get("verdict") == "feasible"
    ]
    if not feasible:
        raise NotShippable("no feasible route")
    chosen = options.get("chosen")
    if chosen in feasible:
        return chosen
    for route in ROUTE_PRIORITY:
        if route in feasible:
            return route
    return feasible[0]


PIPELINES = ["ghostlock", "dirtyfrag"]


class PipelineChoiceRequired(Exception):
    """Both pipelines are feasible and the entry records no choice."""


def feasible_pipelines(options):
    return [
        entry.get("pipeline")
        for entry in options.get("pipelines", [])
        if entry.get("verdict") == "feasible"
        and entry.get("pipeline") in PIPELINES
    ]


def pipeline_reason(options, name):
    for entry in options.get("pipelines", []):
        if entry.get("pipeline") == name:
            return entry.get("reason", "")
    return ""


def choose_pipeline_order(options, meta):
    """The entry's curation override, or the superset's derived order.

    The superset carries the extractor's `pipeline_order` (highest precedence
    first, only statically feasible entries). `meta.txt` may override it with
    one name or a comma-separated order; every entry of the result must be
    feasible on this kernel.
    """
    feasible = feasible_pipelines(options)
    if not feasible:
        reasons = "; ".join(
            f"{entry.get('pipeline')}: {entry.get('reason', '')}"
            for entry in options.get("pipelines", [])
        )
        raise NotShippable(f"no feasible pipeline ({reasons})")
    raw = (meta.get("pipeline") or "").strip().strip('"').lower()
    order = [name.strip() for name in raw.split(",") if name.strip()]
    if not order:
        order = [
            str(name).strip().lower()
            for name in options.get("pipeline_order", [])
            if str(name).strip()
        ]
    if not order:
        # A superset written before the ordered list carried only verdicts;
        # use the extractor's default ranking.
        order = (
            ["dirtyfrag", "ghostlock"]
            if "dirtyfrag" in feasible and "ghostlock" in feasible
            else feasible[:1]
        )
    seen = set()
    for name in order:
        if name not in PIPELINES:
            raise PipelineChoiceRequired(f"unknown pipeline {name}")
        if name not in feasible:
            raise PipelineChoiceRequired(
                f"pipeline {name} is not feasible: {pipeline_reason(options, name)}"
            )
        if name in seen:
            raise PipelineChoiceRequired(f"duplicate pipeline {name}")
        seen.add(name)
    return order


def _asset_document(path):
    """The asset parsed as a value tree (None when unreadable)."""
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        return None
    try:
        return migrate_profile_shape.Parser(text, path).document()
    except migrate_profile_shape.ParseError:
        return None


def _cleaned_asset(path):
    """The asset as the cleaned document (None when it is not one)."""
    document = _asset_document(path)
    if document is None or not isinstance(document.get("release"), str):
        return None
    if not isinstance(document.get("kernel"), dict):
        return None
    return document


def asset_pipeline_order(path):
    """The fixed ladder the cleaned asset's verdict derives, or None."""
    document = _cleaned_asset(path)
    if document is None:
        return None
    ghostlock = document.get("ghostlock")
    timer = document.get("timer")
    kernel = document.get("kernel") or {}
    if ghostlock is None:
        return ["timer"] if timer is not None else None
    if isinstance(ghostlock, dict) and ghostlock.get("module") is not None:
        return ["module"]
    if kernel.get("kdp") is True:
        return ["dirtyfrag"]
    if kernel.get("dirtyfrag") == "feasible":
        return ["dirtyfrag", "ghostlock"]
    return ["ghostlock"]

def asset_has_stride(path):
    """Whether the cleaned asset states `kernel.heap.stride`."""
    document = _cleaned_asset(path)
    if document is None:
        return False
    heap = (document.get("kernel") or {}).get("heap")
    return isinstance(heap, dict) and heap.get("stride") is not None

MISSING = object()

# `schema_version`/addresses are structural; `kernel_phys_*` is the extractor's
# render-time output with the device sidecars, and `recommend_shizuku` is
# retired from the authored schema, so none of them is compared as a fact.
FACT_SKIP = {
    "schema_version", "kernel_phys_load", "kernel_phys_offset", "recommend_shizuku",
    # The engine selection is the app's default (the alphabetically first
    # viable syscall), not a store fact.
    "ghostlock.syscall", "ghostlock.carrier",
}
WAITER_LAYOUT_ORDER = ("compact", "pi_tree", "task", "lock", "wake_state")


def _strip_comments(text):
    lines = []
    for line in text.splitlines():
        quoted = False
        escaped = False
        cut = len(line)
        for index, char in enumerate(line):
            if escaped:
                escaped = False
                continue
            if char == "\\":
                escaped = True
                continue
            if char == '"':
                quoted = not quoted
                continue
            if char == "#" and not quoted:
                cut = index
                break
        lines.append(line[:cut])
    return "\n".join(lines)


def _conf_tokens(text):
    tokens = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char in " \t\r\n,":
            index += 1
            continue
        if char in "{}[]=":
            tokens.append(char)
            index += 1
            continue
        if char == '"':
            buffer = []
            index += 1
            while index < length:
                if text[index] == "\\" and index + 1 < length:
                    buffer.append(text[index + 1])
                    index += 2
                    continue
                if text[index] == '"':
                    break
                buffer.append(text[index])
                index += 1
            tokens.append(("string", "".join(buffer)))
            index += 1
            continue
        end = index
        while end < length and text[end] not in " \t\r\n,{}[]=":
            end += 1
        tokens.append(("word", text[index:end]))
        index = end
    return tokens


class _ConfParser:
    def __init__(self, tokens):
        self.tokens = tokens
        self.index = 0

    def peek(self):
        return self.tokens[self.index] if self.index < len(self.tokens) else None

    def take(self):
        token = self.peek()
        if token is not None:
            self.index += 1
        return token

    def parse_object(self, terminator=None):
        result = {}
        while True:
            token = self.peek()
            if token is None or (terminator is not None and token == terminator):
                break
            if not (isinstance(token, tuple) and token[0] == "word"):
                self.take()
                continue
            key = self.take()[1]
            if self.peek() == "=":
                self.take()
                result[key] = self.parse_value()
            elif self.peek() == "{":
                self.take()
                block = self.parse_object("}")
                if isinstance(result.get(key), dict) and isinstance(block, dict):
                    result[key].update(block)
                else:
                    result[key] = block
            else:
                self.take()
        if terminator is not None and self.peek() == terminator:
            self.take()
        return result

    def parse_value(self):
        token = self.take()
        if token == "{":
            return self.parse_object("}")
        if token == "[":
            values = []
            while self.peek() not in ("]", None):
                values.append(self.parse_value())
            if self.peek() == "]":
                self.take()
            return values
        if isinstance(token, tuple):
            kind, value = token
            if kind == "string":
                return value
            if value == "true":
                return True
            if value == "false":
                return False
            if value == "null":
                return None
            try:
                return int(value)
            except ValueError:
                return value
        return None


def _load_conf_text(path, depth=0):
    if depth > 4:
        return ""
    text = open(path, encoding="utf-8").read()

    def include(match):
        included = os.path.join(os.path.dirname(path), match.group(1))
        try:
            return _load_conf_text(included, depth + 1)
        except OSError:
            return ""

    return re.sub(r'(?m)^\s*include\s+"([^"]+)"\s*$', include, text)


def load_conf_facts(path):
    """The effective asset document (one include level, comments stripped)."""
    text = _strip_comments(_load_conf_text(path))
    return _ConfParser(_conf_tokens(text)).parse_object()


def flatten_facts(value, prefix=""):
    flat = {}
    for key, item in value.items():
        name = f"{prefix}.{key}" if prefix else key
        if isinstance(item, dict):
            flat.update(flatten_facts(item, name))
        else:
            flat[name] = item
    return flat


def _store_value(value):
    if value is None or isinstance(value, (int, bool)):
        return value
    text = str(value).strip()
    if text == "null":
        return None
    if text in ("true", "false"):
        return text == "true"
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        return text[1:-1]
    try:
        return int(text)
    except ValueError:
        return text


def expected_facts(entry):
    """The store superset's derived facts: the profile plus the chosen route's geometry."""
    facts = {}
    profile = entry["options"].get("profile") or {}
    for key, value in (profile.get("") or {}).items():
        facts[key] = _store_value(value)
    for key, value in profile.items():
        if not key:
            continue
        if isinstance(value, dict):
            for nested, nested_value in flatten_facts(value, key).items():
                facts[nested] = _store_value(nested_value)
        else:
            facts[key] = _store_value(value)
    for device in entry["options"].get("devices") or []:
        if device.get("device") != entry["primary"]:
            continue
        for key in ("kernel_phys_load", "kernel_phys_offset"):
            if key in device:
                facts[key] = _store_value(device[key])
    for route in entry["options"].get("routes", []):
        if route.get("route") != entry["route"]:
            continue
        for key, value in (route.get("geometry") or {}).items():
            facts[f"route.{entry['route']}.{key}"] = _store_value(value)
    return facts


def _asset_fact(actual, key, entry):
    if key in ("kernel_phys_load", "kernel_phys_offset"):
        mapped = actual.get(f"{key}.{entry['primary']}")
        if mapped is not None:
            return mapped
    return actual.get(key, MISSING)


def _nested_from_flat(flat):
    root = {}
    for path, value in flat.items():
        node = root
        parts = path.split(".")
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = value
    return root


TIMER_BLOCK = "timer_98260"
TIMER_TOP_KEYS = [
    "version",
    "trigger",
    "timer_site_lower_21bit", "timer_object_size", "cred_request_size", "cred_alloc_size", "cred_uid_offset",
    "static_timer_site", "task_alloc_site_lower_21bit", "task_alloc_size", "task_cred_offset",
    "selinux_state", "panic_on_warn", "root_user", "init_user_ns",
    "init_ucounts", "init_groups",
    "cred_usage_size", "cred_caps_offset", "cred_caps_count", "cred_security_offset",
    "cred_user_offset", "cred_user_ns_offset", "cred_ucounts_offset",
    "cred_group_info_offset", "security_sid_offset",
    "timer_node_offset", "page_alloc_gfp_offset", "page_alloc_gfp_size",
]
TIMER_COMMENT = (
    "# Timer route (CVE-2026-98260): the Shizuku-delivered shell tool's per-image\n"
    "# constants. Not a native route; the native wire never carries this block.\n"
)


def timer_block(options):
    """The store's shipped timer block (a dict) or None."""
    timer = options.get(TIMER_BLOCK)
    if not isinstance(timer, dict):
        return None
    block = timer.get("block")
    return block if isinstance(block, dict) else None


def _hocon_value(value):
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, bool):  # pragma: no cover - bool handled above
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    if isinstance(value, str):
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    raise ValueError(f"unsupported timer value {value!r}")


def render_timer_block(block):
    """The `timer_98260` block as HOCON text (numbers stay numbers, 64-bit
    kernel addresses stay quoted hex strings)."""
    lines = [f"{TIMER_BLOCK} {{"]
    for key in TIMER_TOP_KEYS:
        if key in block:
            lines.append(f"  {key} = {_hocon_value(block[key])}")
    lines.append("}")
    return "\n".join(lines)


def timer_block_facts(block):
    """The block's fields as dotted facts, for the asset comparison."""
    facts = {}
    for key in TIMER_TOP_KEYS:
        if key in block:
            facts[f"{TIMER_BLOCK}.{key}"] = block[key]
    return facts




def _expected_clean_document(entry):
    """The store's derived document in the cleaned shape."""
    flat = expected_facts(entry)
    # The store states the physical pair as a scalar (or null); the cleaned
    # document states it per device.
    for key in ("kernel_phys_load", "kernel_phys_offset"):
        if key in flat and not isinstance(flat[key], dict):
            value = flat.pop(key)
            flat[f"{key}.{entry['primary']}"] = value
    nested = _nested_from_flat(flat)
    # The store's profile section omits the pipeline and the timer block (the
    # ship supplies both); add them so the conversion can derive the same
    # verdict and the same shared symbols the asset states.
    try:
        nested["pipeline"] = choose_pipeline_order(entry["options"], entry["meta"])
    except PipelineChoiceRequired:
        pass
    block = timer_block(entry["options"])
    if block is not None:
        nested["timer_98260"] = block
    return migrate_profile_shape.convert(nested)


def fact_mismatches(entry, asset_path):
    """The asset's derived facts that are absent or differ from the store's."""
    expected_clean = _expected_clean_document(entry)
    expected = flatten_facts(expected_clean) if expected_clean else {}
    actual_document = _cleaned_asset(asset_path)
    actual = flatten_facts(actual_document) if actual_document else {}
    mismatched = []
    for key in sorted(expected):
        if key in FACT_SKIP:
            continue
        wanted = expected[key]
        got = actual.get(key, MISSING)
        if wanted == "<absent>":
            wanted = None
        # A null in the store's sparse profile is not an opinion; presence is
        # the fact the store can assert.
        if wanted is None:
            continue
        if got is MISSING:
            mismatched.append((key, wanted, "<absent>"))
        elif got != wanted:
            mismatched.append((key, wanted, got))
    return mismatched


def timer_block_mismatches(entry, asset_path):
    """The asset's timer facts that are absent or differ from the store's."""
    expected_doc = _expected_clean_document(entry)
    if expected_doc is None or expected_doc.get("timer") is None:
        return []
    expected = flatten_facts({"timer": expected_doc["timer"]})
    actual_doc = _cleaned_asset(asset_path)
    if actual_doc is None or actual_doc.get("timer") is None:
        return [("timer", "present", "<absent>")]
    actual = flatten_facts({"timer": actual_doc["timer"]})
    mismatched = []
    for key in sorted(expected):
        wanted = expected[key]
        got = actual.get(key, MISSING)
        if got is MISSING:
            mismatched.append((key, wanted, "<absent>"))
        elif got != wanted:
            mismatched.append((key, wanted, got))
    return mismatched


def sync_timer_block(entry, asset_path):
    """Insert the timer block into an existing asset, or the block's missing
    keys; never overwrite a value."""
    block = timer_block(entry["options"])
    if block is None:
        return [], []
    mismatched = timer_block_mismatches(entry, asset_path)
    if not mismatched:
        return [], []
    text = open(asset_path, encoding="utf-8").read()
    start = re.search(rf"(?m)^\s*{re.escape(TIMER_BLOCK)}\s*\{{", text)
    if not start:
        text = text.rstrip("\n") + "\n\n" + TIMER_COMMENT + render_timer_block(block) + "\n"
        open(asset_path, "w", encoding="utf-8").write(text)
        return [f"added {TIMER_BLOCK} block"], []
    # The block exists: add its missing keys in the canonical order, keep every
    # existing line, and report value differences as manual.
    lines = text.splitlines(keepends=True)
    start_line = text[: start.start()].count("\n")
    end_line = None
    for index in range(start_line + 1, len(lines)):
        if lines[index].strip() == "}":
            end_line = index
            break
    if end_line is None:
        return [], mismatched
    present = {}
    unknown = []
    for index in range(start_line + 1, end_line):
        match = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*=", lines[index])
        if match:
            present[match.group(1)] = index
        elif lines[index].strip():
            unknown.append(lines[index])
    added = []
    body = []
    for key in TIMER_TOP_KEYS:
        if key in present:
            body.append(lines[present[key]])
        elif key in block:
            body.append(f"  {key} = {_hocon_value(block[key])}\n")
            added.append(key)
    body.extend(unknown)
    if not added:
        return [], mismatched
    new_lines = lines[: start_line + 1] + body + lines[end_line:]
    open(asset_path, "w", encoding="utf-8").write("".join(new_lines))
    manual = [item for item in mismatched if item[0].rsplit(".", 1)[-1] not in added]
    return [f"completed {TIMER_BLOCK}: added {len(added)} keys ({', '.join(added)})"], manual


def _render_literal(value):
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, str):
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return str(value)


def _insert_block_keys(text, block, lines):
    match = re.search(rf"(?m)^(\s*){re.escape(block)}\s*\{{\s*$", text)
    if not match:
        return None
    indent = match.group(1) + "  "
    payload = "".join(f"{indent}{line}\n" for line in lines)
    return text[: match.end() + 1] + payload + text[match.end() + 1:]


def _append_block(text, name, mapping, order):
    body = [
        f"  {key} = {_render_literal(mapping[key])}\n"
        for key in order
        if key in mapping
    ]
    return text.rstrip("\n") + "\n\n" + name + " {\n" + "".join(body) + "}\n"


def _replace_null_line(text, key, value):
    """Fill an explicit `key = null` placeholder in place (no duplicate key)."""
    pattern = re.compile(rf"(?m)^(\s*){re.escape(key)}\s*=\s*null\s*$")
    if not pattern.search(text):
        return None
    return pattern.sub(
        lambda match: f"{match.group(1)}{key} = {_render_literal(value)}",
        text,
        count=1,
    )


def sync_facts(entry, asset_path):
    """Insert the missing derived facts; return (applied, manual) entries.

    Missing keys inside an existing block are inserted there; a missing
    top-level block (waiter_layout, selinux) is appended. A key whose value is
    present but different is reported as manual, never overwritten. The blocks
    owned by a shared include (cred, kernelsnitch) are not duplicated per
    asset - the include itself carries the family values.
    """
    mismatches = fact_mismatches(entry, asset_path)
    if not mismatches:
        return [], []
    expected = expected_facts(entry)
    text = open(asset_path, encoding="utf-8").read()
    applied, manual, missing = [], [], {}
    for key, wanted, got in mismatches:
        if got is None:
            # An explicit `key = null` is an unfilled placeholder, not a
            # disagreeing measured value; fill it in place.
            updated = _replace_null_line(text, key.split(".")[-1], wanted)
            if updated is not None:
                text = updated
                applied.append(f"{key} (null filled)")
                continue
            missing[key] = wanted
            continue
        if got != "<absent>":
            manual.append((key, wanted, got))
        else:
            missing[key] = wanted
    grouped = {}
    for key, wanted in missing.items():
        parts = key.split(".")
        if key.startswith("route."):
            grouped.setdefault(parts[1], []).append((parts[2], wanted))
        else:
            grouped.setdefault(("new", parts[0]), []).append((".".join(parts[1:]), wanted))
    for group, items in grouped.items():
        if isinstance(group, tuple):
            block = group[1]
            if not re.search(rf"(?m)^\s*{re.escape(block)}\s*\{{", text):
                if block in ("cred", "kernelsnitch"):
                    manual.extend(
                        (f"{block}.{key}", wanted, "<include-owned>")
                        for key, wanted in items
                    )
                    continue
                mapping = {key: wanted for key, wanted in items if "." not in key}
                for key, wanted in items:
                    if "." in key:
                        manual.append((f"{block}.{key}", wanted, "<nested>"))
                order = {
                    "waiter_layout": WAITER_LAYOUT_ORDER,
                    "selinux": ("enforcing", "initialized"),
                }.get(block, sorted(mapping))
                text = _append_block(text, block, mapping, order)
                applied.append(f"added {block} block")
                continue
            block_name = block
        else:
            block_name = group
        lines = [f"{key} = {_render_literal(wanted)}" for key, wanted in items]
        updated = _insert_block_keys(text, block_name, lines)
        if updated is None:
            manual.extend(
                (f"{block_name}.{key}", wanted, "<block-not-found>")
                for key, wanted in items
            )
            continue
        text = updated
        applied.extend(f"{block_name}.{key}" for key, _ in items)
    open(asset_path, "w", encoding="utf-8").write(text)
    return applied, manual


def phys_map_lines(options):
    """The two lines that make every device in the superset's devices[] resolve."""
    devices = options.get("devices") or []
    if not devices:
        return None
    loads = [
        (entry.get("device"), int(entry["kernel_phys_load"]))
        for entry in devices
        if entry.get("kernel_phys_load") is not None
    ]
    offsets = [
        (entry.get("device"), int(entry["kernel_phys_offset"]))
        for entry in devices
        if entry.get("kernel_phys_offset") is not None
    ]
    if loads and offsets and {name for name, _ in loads} != {name for name, _ in offsets}:
        raise NotShippable(
            "devices[] mixes null and non-null physical pairs; a map cannot carry "
            "both a null device and the others"
        )

    def render(items):
        if not items:
            return "null"
        return "{ " + ", ".join(f"{name} = {value}" for name, value in items) + " }"

    return (
        f"kernel_phys_load = {render(loads)}",
        f"kernel_phys_offset = {render(offsets)}",
    )




def mm_struct_stride(directory, meta=None):
    """The measured /proc/slabinfo mm_struct objsize, or None.

    The extractor deliberately emits no stride (a generated profile should
    measure it, not seed it); the store's QEMU runtime dump is the usual
    measurement, and a device-only entry records it in meta.txt (`stride`).
    """
    for path in sorted(glob.glob(os.path.join(directory, "runtime-*.txt"))):
        for line in open(path, encoding="utf-8", errors="replace"):
            parts = line.split()
            if len(parts) >= 5 and parts[0] == "SLAB" and parts[1] == "mm_struct":
                try:
                    return int(parts[4])
                except ValueError:
                    return None
    if meta:
        value = (meta.get("stride") or "").strip()
        if value.isdigit():
            return int(value)
    return None


def set_mm_struct_stride(text, stride):
    """Write the measured slab stride into the kernelsnitch block."""
    line = (
        f"  # measured /proc/slabinfo mm_struct objsize {stride}.\n"
        f"  mm_struct_sz = {stride}\n"
    )
    existing = re.compile(r"^  mm_struct_sz\s*=\s*\d+\s*$", re.MULTILINE)
    if existing.search(text):
        return existing.sub(f"  mm_struct_sz = {stride}", text, count=1)
    block = re.search(r"^kernelsnitch\s*\{\s*\n", text, re.MULTILINE)
    if block:
        return text[: block.end()] + line + text[block.end():]
    return text + f"\nkernelsnitch {{\n{line}}}\n"


def replace_line(text, key, line):
    pattern = re.compile(rf"^{re.escape(key)}\s*=.*$", re.MULTILINE)
    if not pattern.search(text):
        raise RuntimeError(f"rendered profile has no {key} line")
    return pattern.sub(line, text, count=1)


def candidates(store):
    """Entries that are not excluded by corpus; no IO on the Image or superset yet."""
    found, exempt = [], []
    for meta_path in sorted(glob.glob(os.path.join(store, "*", "meta.txt"))):
        directory = os.path.dirname(meta_path)
        meta = parse_meta(directory)
        release = meta.get("release", "").strip().strip('"')
        if not release:
            continue
        corpus = meta.get("corpus", "")
        if corpus in SKIP_CORPUS:
            exempt.append((release, f"corpus = {corpus}"))
            continue
        found.append({"release": release, "directory": directory, "meta": meta})
    return found, exempt


def feasible_route_names(options):
    return sorted(
        route.get("route")
        for route in options.get("routes", [])
        if route.get("verdict") == "feasible"
    )


def asset_route_names(path):
    """The syscall branches the cleaned asset declares (non-null blocks)."""
    document = _cleaned_asset(path)
    if document is None:
        return []
    ghostlock = document.get("ghostlock")
    if not isinstance(ghostlock, dict):
        return []
    syscalls = ghostlock.get("syscalls")
    if not isinstance(syscalls, dict):
        return []
    return [name for name, branch in syscalls.items() if branch is not None]

def validate(entry):
    options = read_options(entry["directory"])
    devices = entry_devices(entry["meta"], entry["directory"])
    if not devices:
        raise NotShippable("no device sidecar set")
    entry.update(
        options=options,
        image=entry_image(entry["meta"], entry["directory"]),
        devices=devices,
        primary=devices[0],
        route=choose_route(options),
        routes=feasible_route_names(options),
        maps=phys_map_lines(options),
        stride=mm_struct_stride(entry["directory"], entry["meta"]),
    )
    entry["feasible_pipelines"] = feasible_pipelines(options)
    return entry


def index_releases(assets):
    text = open(os.path.join(assets, "index.conf"), encoding="utf-8").read()
    return set(re.findall(r'release\s*=\s*"([^"]+)"', text))


def add_index_entries(text, releases):
    if not releases:
        return text
    lines = text.splitlines(keepends=True)
    start = next(
        index for index, line in enumerate(lines) if line.strip().startswith("profiles = [")
    )
    end = next(
        index for index in range(start + 1, len(lines)) if lines[index].strip() == "]"
    )
    marker = "  # GOLDEN: every test-kernels golden is shipped (tools/ship_golden_profiles.py).\n"
    block = []
    if not any(line.strip() == marker.strip() for line in lines):
        block.append(marker)
    block += [f'  {{ release = "{release}", file = "{release}.conf" }}\n' for release in releases]
    if lines[end - 1] and not lines[end - 1].endswith("\n"):
        lines[end - 1] += "\n"
    lines[end:end] = block
    return "".join(lines)


def render(extract, entry, out_path):
    command = [extract, entry["image"]]
    command += sidecar_args(entry["directory"], entry["primary"])
    command += [
        "--format", "conf",
        "--anchor",
        "--device", entry["primary"],
        "--pipeline-order", ",".join(entry["choice_pipeline_order"]),
        "--out", out_path,
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(
            f"extractor exited {result.returncode}\n{result.stderr.strip()[-2000:]}"
        )
    if not os.path.isfile(out_path):
        raise RuntimeError("extractor wrote no profile")


def _hex_literal(value):
    return None if value is None else "0x" + format(int(value) & 0xFFFFFFFFFFFFFFFF, "x")


def _address(text):
    if text is None:
        return None
    text = str(text).strip()
    if text == "null" or text == "":
        return None
    if text.lower().startswith("0x"):
        return int(text, 16)
    return int(text, 10)


def cleaned_timer(block, kernel):
    """The store's timer block as the cleaned `timer` block, plus the shared
    kernel facts it owns (the five symbols and the credential sid)."""
    base = _address(kernel.get("image_base"))
    def relative(key):
        value = _address(block.get(key))
        if value is None or base is None or value < base:
            return None
        return value - base
    def literal(value):
        return None if value is None else _hex_literal(value)
    site = _address(block.get("static_timer_site"))
    site_offset = None if site is None else (site - base if base is not None and site >= base else site)
    low = _address(block.get("task_alloc_site_lower_21bit"))
    timer = {
        "trigger": block.get("trigger") if block.get("trigger") in ("killable", "core_state") else "killable",
        "match_bits": 21,
        "sites": {
            "timer": {"offset": literal(site_offset)},
            "task_alloc": {"low_bits": literal(None if low is None else low & 0x1FFFFF)},
        },
        "object_size": _address(block.get("timer_object_size")),
        "task_alloc_size": _address(block.get("task_alloc_size")),
        "node_offset": _address(block.get("timer_node_offset")),
        "cred": {
            "request_size": _address(block.get("cred_request_size")),
            "alloc_size": _address(block.get("cred_alloc_size")),
        },
        "trace": {
            "gfp": {
                "offset": _address(block.get("page_alloc_gfp_offset")),
                "size": _address(block.get("page_alloc_gfp_size")),
            },
        },
    }
    symbols = kernel.setdefault("symbols", {})
    for name in ("panic_on_warn", "root_user", "init_user_ns", "init_ucounts", "init_groups"):
        value = relative(name)
        if value is not None:
            symbols[name] = literal(value)
    structure = kernel.setdefault("struct", {})
    cred = structure.setdefault("cred", {})
    sid = _address(block.get("security_sid_offset"))
    if sid is not None:
        cred["security_sid"] = sid
        if base is not None:
            # The timer's overlay is the derived answer: state its layout in
            # the shared credential block so both engines read one layout.
            for key, path in (
                ("cred_user_offset", ("refs", "user", "offset")),
                ("cred_user_ns_offset", ("refs", "user_ns", "offset")),
                ("cred_ucounts_offset", ("refs", "ucounts", "offset")),
                ("cred_group_info_offset", ("refs", "group_info", "offset")),
            ):
                value = _address(block.get(key))
                if value is not None:
                    node = cred
                    for part in path[:-1]:
                        node = node.setdefault(part, {})
                    node[path[-1]] = value
    return timer


def asset_text(entry, rendered):
    """The extractor's cleaned render with the store-only facts merged in."""
    document = migrate_profile_shape.Parser(rendered, entry["release"]).document()
    devices = {}
    for device in entry["options"].get("devices") or []:
        name = device.get("device")
        if not name:
            continue
        devices[name] = {
            "phys": {
                "load": _hex_literal(device.get("kernel_phys_load")),
                "offset": _hex_literal(device.get("kernel_phys_offset")),
            },
        }
    if devices:
        document["devices"] = devices
    kernel = document.get("kernel")
    if isinstance(kernel, dict):
        heap = kernel.setdefault("heap", {})
        if entry.get("stride"):
            heap["stride"] = int(entry["stride"])
    block = timer_block(entry["options"])
    if block is not None and isinstance(kernel, dict):
        document["timer"] = cleaned_timer(block, kernel)
    body = migrate_profile_shape.render(document)
    route_label = ", ".join(entry["routes"])
    header = (
        "# SHIPPED GOLDEN -- test-kernels golden (QEMU tier); generated, do not edit.\n"
        f"# store: {entry['release']}; routes: {route_label}; "
        f"devices: {', '.join(entry['devices'])}\n"
        "# maintained by external/ghostlock-app/tools/ship_golden_profiles.py\n"
    )
    return header + body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report only, exit 1 on a missing or mismatched golden")
    parser.add_argument("--apply", action="store_true", help="render and index the missing goldens")
    parser.add_argument("--refresh", action="store_true", help="rewrite existing SHIPPED GOLDEN assets whose pipeline line or measured mm_struct_sz is missing/infeasible")
    parser.add_argument("--refresh-all", action="store_true", help="re-render every shipped golden from the store as the route superset")
    parser.add_argument("--sync-facts", action="store_true", help="insert missing derived facts (layout/route/offset blocks) into existing assets; report value differences as manual")
    parser.add_argument("--store", default=DEFAULT_STORE)
    parser.add_argument("--assets", default=DEFAULT_ASSETS)
    parser.add_argument("--extract", default=DEFAULT_EXTRACT)
    parser.add_argument(
        "--only", action="append", default=[],
        help="limit to releases matching any given substring (debugging)",
    )
    args = parser.parse_args()
    if args.check and (args.apply or args.refresh or args.refresh_all or args.sync_facts):
        parser.error("--check is mutually exclusive with --apply/--refresh/--refresh-all/--sync-facts")
    if args.sync_facts and (args.apply or args.refresh or args.refresh_all):
        parser.error("--sync-facts is mutually exclusive with --apply/--refresh/--refresh-all")

    found, exempt = candidates(args.store)
    index = index_releases(args.assets)
    assets = {
        os.path.basename(path)[:-5]
        for path in glob.glob(os.path.join(args.assets, "*.conf"))
    }

    if args.refresh_all:
        if not os.path.isfile(args.extract):
            parser.error(f"extractor not found: {args.extract} (build tools/extract_rs)")
        refreshed, failed = [], []
        for entry in found:
            release = entry["release"]
            if args.only and not any(needle in release for needle in args.only):
                continue
            asset_path = os.path.join(args.assets, release + ".conf")
            if not os.path.isfile(asset_path):
                continue
            try:
                validate(entry)
                entry["choice_pipeline_order"] = choose_pipeline_order(
                    entry["options"], entry["meta"]
                )
            except NotShippable:
                continue
            except PipelineChoiceRequired as error:
                failed.append((release, str(error)))
                continue
            with tempfile.NamedTemporaryFile("w", suffix=".conf", delete=False) as handle:
                temp_path = handle.name
            try:
                render(args.extract, entry, temp_path)
                rendered = open(temp_path, encoding="utf-8").read()
            except Exception as error:  # noqa: BLE001 - report each entry, keep going
                failed.append((release, str(error)))
                continue
            finally:
                if os.path.exists(temp_path):
                    os.unlink(temp_path)
            with open(asset_path, "w", encoding="utf-8") as handle:
                handle.write(asset_text(entry, rendered))
            refreshed.append(release)
        for release in refreshed:
            print(f"refreshed {release}")
        for release, error in failed:
            print(f"FAILED  {release}: {error}", file=sys.stderr)
        print(f"refreshed {len(refreshed)}, failed {len(failed)}")
        return 1 if failed else 0

    bundled = 0
    missing = []
    mismatched_entries = []
    for entry in found:
        release = entry["release"]
        if args.only and not any(needle in release for needle in args.only):
            continue
        asset_path = os.path.join(args.assets, release + ".conf")
        has_asset = os.path.isfile(asset_path)
        try:
            validate(entry)
        except NotShippable as error:
            reason = str(error)
            # A hand-finished asset is the documented exception when the store
            # entry has no superset / no sidecar set / mixed physical pairs.
            # A stale asset on a kernel with no feasible route or pipeline is
            # a real content failure.
            hand_finished = has_asset and not any(
                phrase in reason
                for phrase in ("no feasible route", "no feasible pipeline")
            )
            if hand_finished:
                bundled += 1
            elif has_asset:
                entry["reasons"] = [f"asset present but entry is not shippable: {reason}"]
                missing.append(entry)
            else:
                exempt.append((release, reason))
            continue
        feasible = entry["feasible_pipelines"]
        if has_asset:
            if not feasible:
                entry["reasons"] = ["asset present but no feasible pipeline"]
                missing.append(entry)
                continue
            try:
                expected = choose_pipeline_order(entry["options"], entry["meta"])
            except PipelineChoiceRequired as error:
                entry["reasons"] = [str(error)]
                missing.append(entry)
                continue
            chosen = asset_pipeline_order(asset_path)
            if chosen is None:
                entry["reasons"] = [
                    f"asset has no pipeline line (expected {', '.join(expected)})"
                ]
                missing.append(entry)
                continue
            if chosen != expected:
                entry["reasons"] = [
                    f"asset pipeline {chosen} != derived order {expected}"
                ]
                missing.append(entry)
                continue
            asset_routes = asset_route_names(asset_path)
            # The cleaned document has no route order: compare as a set.
            if sorted(asset_routes) != sorted(entry["routes"]):
                entry["reasons"] = [
                    f"asset routes {asset_routes} != derived {entry['routes']}"
                ]
                mismatched_entries.append(entry)
                continue
            if release not in index:
                entry["reasons"] = ["asset present but not in index.conf"]
                missing.append(entry)
                continue
            if entry.get("stride") and not asset_has_stride(asset_path):
                entry["reasons"] = [
                    f"asset has no mm_struct_sz (entry stride {entry['stride']}); "
                    "use --refresh"
                ]
                missing.append(entry)
                continue
            facts = fact_mismatches(entry, asset_path)
            facts += timer_block_mismatches(entry, asset_path)
            if facts:
                entry["reasons"] = [
                    f"{key}: expected {wanted!r}, got {got!r}"
                    for key, wanted, got in facts[:6]
                ]
                if len(facts) > 6:
                    entry["reasons"].append(f"... {len(facts) - 6} more")
                mismatched_entries.append(entry)
                continue
            bundled += 1
            continue
        try:
            entry["choice_pipeline_order"] = choose_pipeline_order(
                entry["options"], entry["meta"]
            )
        except PipelineChoiceRequired as error:
            entry["reasons"] = [str(error)]
            missing.append(entry)
            continue
        except NotShippable as error:
            exempt.append((release, str(error)))
            continue
        entry["reasons"] = []
        if release not in index:
            entry["reasons"].append("not in index.conf")
        entry["reasons"].append("no asset file")
        missing.append(entry)

    if args.sync_facts:
        for entry in mismatched_entries:
            asset_path = os.path.join(args.assets, entry["release"] + ".conf")
            applied, manual = sync_facts(entry, asset_path)
            block_applied, block_manual = sync_timer_block(entry, asset_path)
            applied += block_applied
            manual += block_manual
            print(f"SYNC {entry['release']}: {len(applied)} inserted, {len(manual)} manual")
            for item in applied:
                print(f"   + {item}")
            for key, wanted, got in manual:
                print(f"   MANUAL {key}: expected {wanted!r}, got {got!r}")
        remaining = 0
        for entry in mismatched_entries:
            asset_path = os.path.join(args.assets, entry["release"] + ".conf")
            left = fact_mismatches(entry, asset_path)
            left += timer_block_mismatches(entry, asset_path)
            if left:
                remaining += 1
                print(f"REMAINING {entry['release']}: {len(left)}")
                for key, wanted, got in left[:6]:
                    print(f"   {key}: expected {wanted!r}, got {got!r}")
        print(f"sync: {len(mismatched_entries)} entries touched, {remaining} still mismatched")
        return 1 if remaining else 0

    if args.check:
        for entry in missing:
            print(f"MISSING {entry['release']}: {', '.join(entry['reasons'])}")
        for entry in mismatched_entries:
            print(f"MISMATCH {entry['release']}: {', '.join(entry['reasons'])}")
        for name in sorted(index):
            if name not in assets:
                print(f"ORPHAN index entry {name}: no asset file")
        print(
            f"{bundled} bundled, {len(missing)} missing, "
            f"{len(mismatched_entries)} mismatched, {len(exempt)} exempt"
        )
        return 1 if missing or mismatched_entries else 0

    if not missing:
        for entry in mismatched_entries:
            print(f"MISMATCH {entry['release']}: {', '.join(entry['reasons'])}")
        print(
            f"{bundled} bundled, 0 missing, "
            f"{len(mismatched_entries)} mismatched, {len(exempt)} exempt"
        )
        return 1 if mismatched_entries else 0
    if not os.path.isfile(args.extract):
        parser.error(f"extractor not found: {args.extract} (build tools/extract_rs)")

    shipped = []
    refreshed = []
    failures = []
    index_path = os.path.join(args.assets, "index.conf")
    for entry in missing:
        asset_path = os.path.join(args.assets, entry["release"] + ".conf")
        exists = os.path.isfile(asset_path)
        if exists and not args.refresh:
            failures.append((entry["release"], "asset exists; use --refresh to rewrite it"))
            continue
        if exists:
            first = open(asset_path, encoding="utf-8").readline()
            if not first.startswith("# SHIPPED GOLDEN"):
                failures.append((entry["release"], "asset is hand-finished; not overwritten"))
                continue
        if "choice_pipeline_order" not in entry:
            try:
                entry["choice_pipeline_order"] = choose_pipeline_order(
                    entry["options"], entry["meta"]
                )
            except (PipelineChoiceRequired, NotShippable) as error:
                failures.append((entry["release"], str(error)))
                continue
        with tempfile.NamedTemporaryFile("w", suffix=".conf", delete=False) as handle:
            temp_path = handle.name
        try:
            render(args.extract, entry, temp_path)
            rendered = open(temp_path, encoding="utf-8").read()
        except Exception as error:  # noqa: BLE001 - report each entry, keep going
            failures.append((entry["release"], str(error)))
            continue
        finally:
            if os.path.exists(temp_path):
                os.unlink(temp_path)
        with open(asset_path, "w", encoding="utf-8") as handle:
            handle.write(asset_text(entry, rendered))
        if exists:
            refreshed.append(entry["release"])
        else:
            shipped.append(entry["release"])

    new_index = [release for release in sorted(shipped) if release not in index]
    if new_index:
        text = open(index_path, encoding="utf-8").read()
        text = add_index_entries(text, new_index)
        with open(index_path, "w", encoding="utf-8") as handle:
            handle.write(text)

    for release in shipped:
        print(f"shipped {release}")
    for release in refreshed:
        print(f"refreshed {release}")
    for release, reason in exempt:
        print(f"exempt  {release}: {reason}")
    for release, error in failures:
        print(f"FAILED  {release}: {error}", file=sys.stderr)
    for entry in mismatched_entries:
        print(f"MISMATCH {entry['release']}: {', '.join(entry['reasons'])}")
    print(
        f"shipped {len(shipped)}, refreshed {len(refreshed)}, exempt {len(exempt)}, "
        f"failed {len(failures)}, mismatched {len(mismatched_entries)}, "
        f"already bundled {bundled}"
    )
    return 1 if failures or mismatched_entries else 0


if __name__ == "__main__":
    sys.exit(main())
