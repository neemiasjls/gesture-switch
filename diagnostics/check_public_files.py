"""Check Git publication candidates without displaying private values. Standard library only."""
import argparse
import configparser
from pathlib import Path
import re
import subprocess
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    "IP numerico": re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])"),
    "MAC": re.compile(r"\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b", re.I),
    "UUID": re.compile(r"\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b", re.I),
    "email": re.compile(r"\b[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}\b"),
    "convite privado": re.compile(r"https?://m-us\.smart321\.com/\S+"),
    "caminho pessoal": re.compile(r"C:[/\\]Users[/\\][^/\\\s]+", re.I),
    "credencial GitHub": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"),
}


def git(*arguments):
    return subprocess.run(["git", *arguments], cwd=ROOT, capture_output=True, check=True).stdout


def private_values():
    config = configparser.ConfigParser(interpolation=None)
    template = configparser.ConfigParser(interpolation=None)
    try:
        config.read(ROOT / ".secrets/config.ini", encoding="utf-8-sig")
        template.read(ROOT / "config.example.ini", encoding="utf-8-sig")
    except (configparser.Error, UnicodeError):
        raise ValueError("Configuracao privada invalida; verificacao interrompida sem exibir valores.") from None
    return extract_private_values(config, template)


def extract_private_values(config, template):
    values = set()
    sensitive_keys = {"token", "local_key", "device_id", "id", "suffix", "ip", "mac", "url", "cidr",
                      "access_id", "access_secret", "client_secret", "password"}
    for section in config.sections():
        for key, value in config.items(section):
            if value == template.get(section, key, fallback=None):
                continue
            if (key in sensitive_keys or key.startswith("invitation_")) and len(value.strip()) >= 6:
                values.add(value.strip())
            if key in {"targets", "buttons", "lights", "entity_ids"}:
                values.update(part.strip() for part in re.split(r"[,|]", value) if len(part.strip()) > 12)
    return values


def inspect_text(text, secrets):
    """Return only line numbers and classifications, never matched content."""
    findings = []
    for number, line in enumerate(text.splitlines(), 1):
        if any(value in line for value in secrets):
            findings.append((number, "valor da configuracao privada"))
        findings.extend((number, label) for label, pattern in PATTERNS.items() if pattern.search(line))
    return findings


def assistant_artifact(name):
    """Assistant instructions and conversation memory stay local, even when force-added."""
    normalized = unicodedata.normalize("NFKD", name.replace("\\", "/").lower())
    parts = normalized.encode("ascii", "ignore").decode("ascii").split("/")
    directories = {".codex", ".agents", ".cursor", ".windsurf", "memory", "memories",
                   "memoria", "memorias", "contexto"}
    if any("claude" in part or part in directories or part.startswith(".aider") for part in parts):
        return True
    filename = parts[-1]
    return filename.endswith(".md") and filename.startswith(
        ("agents", "memory", "memoria", "handoff", "context", "contexto"))


def check(staged=False):
    arguments = ("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z") if staged else (
        "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    names = sorted(set(name.decode("utf-8") for name in git(*arguments).split(b"\0") if name))
    secrets = private_values()
    failures = []
    for name in names:
        if assistant_artifact(name):
            failures.append((name, 0, "contexto ou memoria de assistente deve permanecer local"))
        if name.startswith(".secrets/") or Path(name).name.startswith(".env") and Path(name).name != ".env.example":
            failures.append((name, 0, "arquivo privado incluido no Git"))
        path = ROOT / name
        if not staged and not path.is_file():
            continue
        data = git("show", ":" + name) if staged else path.read_bytes()
        try:
            text = data.decode("utf-8-sig")
        except UnicodeError:
            failures.append((name, 0, "arquivo binario exige revisao manual"))
            continue
        failures.extend((name, number, label) for number, label in inspect_text(text, secrets))
    for name, number, label in failures:
        print(f"{name}:{number}: {label}")
    if failures:
        print("NAO PUBLIQUE: remova ou mova os dados privados antes de continuar.")
        return 1
    print(f"Privacidade OK: {len(names)} arquivos verificados; nenhum valor privado detectado.")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true", help="Verificar o conteudo exato preparado para o commit")
    args = parser.parse_args()
    try:
        sys.exit(check(args.staged))
    except (OSError, ValueError, subprocess.CalledProcessError):
        print("Nao foi possivel concluir a verificacao de privacidade; nao publique antes de corrigir.")
        sys.exit(1)
