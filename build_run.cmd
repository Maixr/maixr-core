@echo off
rem 在脚本所在目录（即工程根目录）构建，输出写到 build_out.txt
cd /d "%~dp0"
rem JAVA_HOME 未设置时回退到本机 .jdks 里的 JDK 21
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Users\Administrator\.jdks\ms-21.0.12"
rem JVM 强制英文，编译器错误信息更好读
set JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US
"%JAVA_HOME%\bin\java.exe" -Dnet.neoforged.gradle.check.certs=false -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build --console=plain --no-daemon > "%~dp0build_out.txt" 2>&1
echo EXIT=%ERRORLEVEL%>> "%~dp0build_out.txt"
