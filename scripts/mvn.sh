#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
source "$mdm_root/scripts/env.sh"
mdm_settings=/workspace/.tools/maven-settings.xml
mkdir -p /workspace/.tools
python - <<'PY'
import os,urllib.parse,pathlib,xml.sax.saxutils
p=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
s='<settings>'
if p.hostname:
 s+=f'<proxies><proxy><id>platform</id><active>true</active><protocol>http</protocol><host>{xml.sax.saxutils.escape(p.hostname)}</host><port>{p.port or 80}</port><nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy></proxies>'
s+='</settings>'
pathlib.Path('/workspace/.tools/maven-settings.xml').write_text(s)
PY
mdm_trust=()
if [ -r /etc/ssl/certs/java/cacerts ]; then mdm_trust=(-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts); fi
cd "$mdm_root/backend"
exec mvn -C -B -s "$mdm_settings" "${mdm_trust[@]}" "$@"
