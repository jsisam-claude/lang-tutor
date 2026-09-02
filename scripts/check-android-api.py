#!/usr/bin/env python3
"""Fail when compiled classes call Java library members Android lacks at minSdk.

Android Lint's NewApi check only reads the sources of Android modules. The
pure-JVM core modules compile against a full JDK and will happily use any
Java 9-21 convenience — Files.writeString, String.strip, Stream.toList —
that Android's libcore added after our minSdk, or years later, or never.
Nothing catches that until a device throws NoSuchMethodError, which is how
the tablet died at its first profile save while the Pixels, whose newer
Android carries the method, never noticed.

This closes the hole for EVERY module the same way: javap every compiled
class, take each java.*/javax.* member it invokes, and ask the SDK whether
minSdk has it. The oracle for EXISTENCE is the minSdk platform's own
android.jar stubs (platforms/android-31), walked through their supertypes,
because those declare exactly what a minSdk device has — including members
inherited from hidden parents (StringBuilder.setLength lives on the
package-private AbstractStringBuilder) that the API database omits, and
excluding members newer platforms gained that the database has not caught
up with (Files.writeString, added in the newest stubs, absent from the
database). The database (api-versions.xml) only supplies the "added in API
N" wording. Without platform 31 installed the newest stubs are used and the
check is loudly weaker. java.lang.invoke is skipped: D8 desugars it.

Usage: scripts/check-android-api.py [class-dir ...]
  default dirs: core/*/build/classes/kotlin/main and app/build/tmp/kotlin-classes/*
  needs: javap on PATH (a JDK), ANDROID_HOME or ANDROID_SDK_ROOT,
         ideally `sdkmanager --install "platforms;android-31"`
"""
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

MIN_SDK = 31
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DIRS = ["core/*/build/classes/kotlin/main", "app/build/tmp/kotlin-classes/*"]
SKIP_OWNER_PREFIXES = ("java/lang/invoke/",)
BATCH = 150


def sdk_root():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sys.exit("ANDROID_HOME (or ANDROID_SDK_ROOT) must point at an SDK with a platform installed")
    return sdk


def platforms(sdk):
    found = {}
    for jar in glob.glob(os.path.join(sdk, "platforms", "android-*", "android.jar")):
        m = re.search(r"android-(\d+)", jar)
        if m:
            found[int(m.group(1))] = jar
    if not found:
        sys.exit(f"no platforms/android-*/android.jar under {sdk}")
    return found


def load_database(jar):
    """api-versions.xml next to the newest platform: class -> (since, supers, {member: (since, removed)})."""
    path = os.path.join(os.path.dirname(jar), "data", "api-versions.xml")
    classes = {}
    if not os.path.exists(path):
        return path, classes
    for c in ET.parse(path).getroot().iter("class"):
        since = int(c.get("since", "1"))
        supers = [e.get("name") for e in c.findall("extends")] + [e.get("name") for e in c.findall("implements")]
        members = {}
        for m in list(c.findall("method")) + list(c.findall("field")):
            members[m.get("name")] = (int(m.get("since", since)), m.get("removed"))
        classes[c.get("name")] = (since, supers, members)
    return path, classes


def db_resolve(classes, owner, member, seen=None):
    seen = seen or set()
    if owner in seen or owner not in classes:
        return None
    seen.add(owner)
    since, supers, members = classes[owner]
    if member in members:
        return members[member]
    for s in supers + (["java/lang/Object"] if owner != "java/lang/Object" else []):
        hit = db_resolve(classes, s, member, seen)
        if hit:
            return hit
    return None


def strip_generics(s):
    while "<" in s:
        s = re.sub(r"<[^<>]*>", "", s)
    return s


class Stubs:
    """Declared members of the stub classes in one android.jar, loaded in batches."""

    def __init__(self, jar):
        self.jar = jar
        self.declared = {}  # class -> set of "name+desc" / field names
        self.supers = {}    # class -> [supertypes]

    def load(self, names):
        """javap the stub CLASS FILES pulled out of the jar with zipfile — never
        by class name: javap resolves java.* names from the running JDK's own
        modules first (even with --system none), and a JDK 25 String has
        strip() and Files has writeString(), which is precisely the lie this
        check exists to catch."""
        names = [n for n in names if n not in self.declared]
        if not names:
            return
        import tempfile
        import zipfile
        with zipfile.ZipFile(self.jar) as jar, tempfile.TemporaryDirectory() as tmp:
            present = set(jar.namelist())
            paths = []
            for n in names:
                entry = n + ".class"
                if entry in present:
                    jar.extract(entry, tmp)
                    paths.append(os.path.join(tmp, entry))
                else:
                    self.declared.setdefault(n, None)  # not in this platform at all
            for i in range(0, len(paths), BATCH):
                p = subprocess.run(["javap", "-s", "-p"] + paths[i:i + BATCH], capture_output=True, text=True)
                current = None
                pending = None
                for line in p.stdout.splitlines():
                    if not line.startswith(" ") and re.search(r"\b(class|interface|enum) ", line) and line.endswith("{"):
                        head = strip_generics(line[:-1])
                        m = re.search(r"\b(?:class|interface|enum) ([\w.$]+)(?: extends ([\w.$, ]+?))?(?: implements ([\w.$, ]+?))?\s*$", head)
                        if not m:
                            current = None
                            continue
                        current = m.group(1).replace(".", "/")
                        sup = []
                        for grp in (m.group(2), m.group(3)):
                            if grp:
                                sup += [x.strip().replace(".", "/") for x in grp.split(",") if x.strip()]
                        self.declared.setdefault(current, set())
                        self.supers[current] = sup
                        pending = None
                        continue
                    if current is None:
                        continue
                    decl = re.match(r"^\s+.*?([\w$<>]+)\(.*\)[^;]*;$", line)
                    if decl:
                        pending = decl.group(1)
                        continue
                    fld = re.match(r"^\s+[\w.$<>\[\], ]+ ([\w$]+);$", strip_generics(line))
                    if fld and "(" not in line:
                        pending = fld.group(1)
                        continue
                    d = re.match(r"^\s+descriptor: (\S+)$", line)
                    if d and pending is not None:
                        self.declared[current].add(pending + d.group(1) if d.group(1).startswith("(") else pending)
                        pending = None
            for n in names:  # anything javap could not parse stays unknown
                self.declared.setdefault(n, None)

    def declares(self, owner, member, seen=None):
        seen = seen or set()
        if owner in seen:
            return False
        seen.add(owner)
        self.load([owner])
        decl = self.declared.get(owner)
        if decl is None:
            return False
        if member in decl:
            return True
        return any(self.declares(s, member, seen) for s in self.supers.get(owner, []))


CALL = re.compile(r"// (?:Interface)?Method ([\w/$]+)\.([\w<>$]+):(\(.*)$")
FIELD = re.compile(r"// Field ([\w/$]+)\.([\w$]+):(\S+)$")


def uses(class_files):
    """{(owner, member): set(callers)} for every java.*/javax.* member invoked."""
    out = {}
    for i in range(0, len(class_files), BATCH):
        p = subprocess.run(["javap", "-c", "-p"] + class_files[i:i + BATCH], capture_output=True, text=True)
        if p.returncode != 0:
            sys.exit(f"javap failed: {p.stderr[:400]}")
        current = None
        for line in p.stdout.splitlines():
            head = re.match(r"^(?:public |final |abstract |synchronized )*(?:class|interface|enum) ([\w.$]+)", line)
            if head:
                current = head.group(1)
                continue
            m = CALL.search(line) or FIELD.search(line)
            if not m:
                continue
            owner, name, desc = m.groups()
            if not owner.startswith(("java/", "javax/")) or owner.startswith(SKIP_OWNER_PREFIXES):
                continue
            member = name + desc if desc.startswith("(") else name
            out.setdefault((owner, member), set()).add(current or "?")
    return out


def main(argv):
    dirs = argv or [d for pat in DEFAULT_DIRS for d in glob.glob(os.path.join(REPO, pat))]
    class_files = [f for d in dirs for f in glob.glob(os.path.join(d, "**", "*.class"), recursive=True)]
    if not class_files:
        sys.exit("no compiled classes found — build first (e.g. ./gradlew :app:compilePracticeDebugKotlin)")
    sdk = sdk_root()
    jars = platforms(sdk)
    newest = jars[max(jars)]
    strict = MIN_SDK in jars
    oracle = jars[MIN_SDK] if strict else newest
    db_path, db = load_database(newest)
    stubs = Stubs(oracle)
    used = uses(class_files)
    stubs.load(sorted({owner for owner, _ in used}))

    findings = {}
    for (owner, member), callers in used.items():
        if stubs.declares(owner, member):
            continue
        info = db_resolve(db, owner, member)
        if info and info[0] <= MIN_SDK and not info[1]:
            continue  # the database vouches for it; the stub parse missed it
        if info and info[1]:
            verdict = f"removed in API {info[1]}"
        elif info:
            verdict = f"added in API {info[0]} > minSdk {MIN_SDK}"
        else:
            verdict = f"not in the Android API at minSdk {MIN_SDK} (nor in the API database)"
        findings[(owner, member, verdict)] = callers

    print(f"checked {len(class_files)} classes: {len(used)} distinct java.* members, "
          f"existence from {os.path.relpath(oracle, sdk)}, levels from {os.path.relpath(db_path, sdk)}")
    if not strict:
        print(f"WARNING: platform android-{MIN_SDK} is not installed, so existence was judged from the newest "
              f"stubs and members newer than minSdk that the database does not know about will pass. "
              f'Install it: sdkmanager --install "platforms;android-{MIN_SDK}"')
    if not findings:
        print(f"OK: every java.*/javax.* member used exists on minSdk {MIN_SDK}")
        return 0
    for (owner, member, verdict), callers in sorted(findings.items()):
        print(f"!! {owner}.{member}: {verdict}")
        for c in sorted(callers):
            print(f"     called from {c}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
