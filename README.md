# xslt-debug-adapter
An [XSLT](https://www.w3.org/TR/xslt/all/) debug server that implements the [Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/).

## Building
`./gradlew build`

## Running

The adapter can run in 2 different modes:

- `./gradlew run` runs as a command. Stdin/stdout are used to receive/send to the client
- `./gradlew run --args {port}` runs as a server. The port number (specified when starting up) is used to communicate

## Testing
`./gradlew test`

`./gradlew formatKotlin`

## Sharing
`./gradlew shadowJar` builds a `./build/libs/xslt-debug-adapter-{version}-all.jar` which is used by the [vscode-xslt-debug](https://github.com/philschatz/vscode-xslt-debug) plugin.