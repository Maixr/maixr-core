@echo off
cd /d "D:\mod creater\mods\MaixrCore\1.21.1neo"
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.11
rem JVM 强制英文，编译器错误信息更好读
set JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US
"C:\Program Files\Java\jdk-21.0.11\bin\java.exe" -Dnet.neoforged.gradle.check.certs=false -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build --console=plain --no-daemon > "D:\mod creater\mods\MaixrCore\1.21.1neo\build_out.txt" 2>&1
echo EXIT=%ERRORLEVEL%>> "D:\mod creater\mods\MaixrCore\1.21.1neo\build_out.txt"
