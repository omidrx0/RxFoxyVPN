"""python -m rxvpn   (also the entry script PyInstaller bundles)."""
import sys

from rxvpn.launcher import main   # absolute import: works as a module and as a frozen entry script

if __name__ == "__main__":
    sys.exit(main())
