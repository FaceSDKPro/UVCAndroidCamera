"""Exercise the source gate with reviewed public binaries and disallowed payloads."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'docs/images/dual-preview-landscape.png'
PUBLIC_KEY = 'sample/uvc-camera-release.jks'


class SourceAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='uvc-source-audit-')
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        (self.repo / 'tools').mkdir()
        shutil.copyfile(ROOT / 'tools/audit_source.py', self.repo / 'tools/audit_source.py')
        (self.repo / IMAGE).parent.mkdir(parents=True)
        shutil.copyfile(ROOT / IMAGE, self.repo / IMAGE)
        (self.repo / 'README.md').write_text('Public documentation fixture.\n')
        self.git('init', '-q')
        self.git('add', 'README.md', IMAGE)

    def git(self, *args):
        subprocess.run(['git', *args], cwd=self.repo, check=True, capture_output=True)

    def audit(self):
        return subprocess.run(['python3', 'tools/audit_source.py'], cwd=self.repo,
                              text=True, capture_output=True)

    def assertRejected(self, reason):
        result = self.audit()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(reason, result.stdout + result.stderr)

    def test_reviewed_public_screenshot_passes(self):
        result = self.audit()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_changed_reviewed_screenshot_is_rejected(self):
        path = self.repo / IMAGE
        path.write_bytes(path.read_bytes() + b'unapproved trailing payload')
        self.git('add', IMAGE)
        self.assertRejected('public binary checksum mismatch')

    def test_reviewed_public_signing_key_passes(self):
        path = self.repo / PUBLIC_KEY
        path.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / PUBLIC_KEY, path)
        self.git('add', PUBLIC_KEY)
        result = self.audit()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_changed_public_signing_key_is_rejected(self):
        path = self.repo / PUBLIC_KEY
        path.parent.mkdir(parents=True)
        path.write_bytes((ROOT / PUBLIC_KEY).read_bytes() + b'unapproved trailing payload')
        self.git('add', PUBLIC_KEY)
        self.assertRejected('public binary checksum mismatch')

    def test_other_image_is_rejected_even_with_the_same_bytes(self):
        name = 'docs/images/unapproved.png'
        shutil.copyfile(self.repo / IMAGE, self.repo / name)
        self.git('add', name)
        self.assertRejected(name + ': unexpected binary')

    def test_signing_material_is_rejected(self):
        (self.repo / 'fixture.jks').write_text('Not a real key; forbidden filename fixture.\n')
        self.git('add', 'fixture.jks')
        self.assertRejected('fixture.jks: generated/private payload')

    def test_credential_shaped_text_is_rejected(self):
        (self.repo / 'fixture.txt').write_text('ghp_' + 'A' * 36 + '\n')
        self.git('add', 'fixture.txt')
        self.assertRejected('fixture.txt: credential-shaped content')

    def test_unstaged_tracked_changes_are_rejected(self):
        (self.repo / 'README.md').write_text('Changed after staging.\n')
        self.assertRejected('Unstaged tracked changes remain')


if __name__ == '__main__':
    unittest.main()
