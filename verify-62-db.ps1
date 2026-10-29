$ErrorActionPreference = 'Stop'
# 6.2 offline db verify: app already stopped; feed SQL via stdin (avoid PS arg-quoting pitfalls)
$h2 = "$env:USERPROFILE\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar"
$java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
$url = 'jdbc:h2:file:d:/Agent-Platform/data/agent-platform;IFEXISTS=TRUE'

$queries = @(
    'SELECT COUNT(*) AS session_residual FROM AI_SESSION WHERE id = ''rpg-verify-session-1026111432'';',
    'SELECT COUNT(*) AS event_residual FROM AI_SESSION_EVENT WHERE session_id = ''rpg-verify-session-1026111432'';',
    'SELECT COUNT(*) AS ai_session_total FROM AI_SESSION;',
    'SELECT COUNT(*) AS kind_rpg_tagged FROM AI_SESSION WHERE CAST(metadata AS VARCHAR) LIKE ''"kind":"rpg"%'';',
    'SELECT COUNT(*) AS chat_with_title FROM AI_SESSION WHERE CAST(metadata AS VARCHAR) LIKE ''"title"%'';'
)

$queries | & $java -cp $h2 org.h2.tools.Shell -url $url -user sa -password ''
