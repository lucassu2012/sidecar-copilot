"""Exclusive ownership before SQLite recovery; uses only standard-library OS locks."""
import os


class RootLease:
    def __init__(self, path):
        self.file = open(path, 'a+b')
        try:
            if os.name == 'nt':
                import msvcrt
                if self.file.seek(0, os.SEEK_END) == 0:
                    self.file.write(b'0'); self.file.flush()
                self.file.seek(0)
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BaseException:
            self.file.close()
            raise RuntimeError('store already owned or lock unavailable') from None

    def close(self):
        if not self.file.closed:
            if os.name == 'nt':
                import msvcrt
                self.file.seek(0)
                msvcrt.locking(self.file.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_UN)
            self.file.close()
