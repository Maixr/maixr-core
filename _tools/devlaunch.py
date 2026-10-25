#!/usr/bin/env python3
"""直接以 ModDevGradle 的方式启动 dev 客户端/服务端（绕开 gradle 项目锁，可并行双开）。

用法：
  python _tools/devlaunch.py server          # build/moddev/serverRun*.txt，cwd=run/
  python _tools/devlaunch.py client          # build/moddev/clientRun*.txt
  python _tools/devlaunch.py server --print  # 只打印命令不执行
  python _tools/devlaunch.py server --dir run-bench   # 指定 gameDir（临时世界测试用，
                                      # 目录需自备 server.properties/eula.txt/mods）

完全镜像 gradle 的 JavaExec 命令（实测抓取自 gradle 进程命令行）：
  java -Dfml.modFolders=maixr%%<classes>;maixr%%<resources> @<vmArgs.txt>
       -Dfile.encoding=UTF-8 -cp <devcp*.txt 或 legacy 组合> net.neoforged.devlaunch.Main @<progArgs.txt>

★ -Dfml.modFolders 是关键：显式声明模组根（classes+resources 两个目录），
  缺了它 FML 自动发现会只认 resources 根，客户端 mixin 类不可见
  （实测症状：Mixin prepare for mod maixr failed ... PlayerInfoSkinMixin was not found）。
cp 来源：run/devcp.txt（server）/ run/devcp-client.txt（client）—— gradle 实际 -cp 的
留存快照；丢失时退回最小组合（合并 jar + client-extra + classes + resources）。
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODDEV = ROOT / "build" / "moddev"
JAVA = pathlib.Path(r"C:\Users\Administrator\.jdks\ms-21.0.12\bin\java.exe")


def main() -> int:
    target = sys.argv[1] if len(sys.argv) > 1 else ""
    dry = "--print" in sys.argv
    if target not in ("server", "client"):
        print(__doc__)
        return 2
    prog = MODDEV / f"{target}RunProgramArgs.txt"
    vm = MODDEV / f"{target}RunVmArgs.txt"
    if not prog.exists() or not vm.exists():
        print(f"缺少 {prog} 或 {vm}（先跑一次 ./gradlew build）")
        return 2

    cp_file = ROOT / "run" / ("devcp-client.txt" if target == "client" else "devcp.txt")
    sep = ";" if sys.platform == "win32" else ":"
    if cp_file.exists():
        cp = cp_file.read_text(encoding="utf-8").strip()
    else:
        cp = sep.join(str(x) for x in [
            MODDEV / "artifacts" / "neoforge-21.1.234.jar",
            MODDEV / "artifacts" / "neoforge-21.1.234-client-extra-aka-minecraft-resources.jar",
            ROOT / "build" / "classes" / "java" / "main",
            ROOT / "build" / "resources" / "main"])

    mod_folders = (f"maixr%%{ROOT / 'build' / 'classes' / 'java' / 'main'}"
                   f";maixr%%{ROOT / 'build' / 'resources' / 'main'}")
    argv = [str(JAVA),
            f"-Dfml.modFolders={mod_folders}",
            f"@{vm}",
            "-Dfile.encoding=UTF-8",
            "-cp", cp,
            "net.neoforged.devlaunch.Main",
            f"@{prog}"]
    if dry:
        print(f"java -Dfml.modFolders={mod_folders[:80]}... @{vm.name} -cp <{cp_file.name}> "
              f"net.neoforged.devlaunch.Main @{prog.name}")
        return 0
    game_dir = ROOT / "run"
    if "--dir" in sys.argv:
        game_dir = ROOT / sys.argv[sys.argv.index("--dir") + 1]
    proc = subprocess.run(argv, cwd=str(game_dir))
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
