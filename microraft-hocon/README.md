# MicroRaft HOCON Config Parser

Gradle (version catalog)

```toml
[versions]
microraft-hocon = "0.9"

[libraries]
microraft-hocon = { module = "io.microraft:microraft-hocon", version.ref = "microraft-hocon" }
```

Gradle (kotlinscript)

```kotlin
implementation("io.microraft:microraft-hocon:0.9")
```

Maven

```xml
<dependency>
    <groupId>io.microraft</groupId>
    <artifactId>microraft-hocon</artifactId>
    <version>0.9</version>
</dependency>
```

This project enables you to create `RaftConfig` objects from HOCON files
easily, as shown below:

```
String configFilePath = "...";
Config hoconConfig = ConfigFactory.parseFile(new File(configFilePath));
RaftConfig raftConfig = HoconRaftConfigParser.parseConfig(hoconConfig);
``` 

Other than reading your config from a file, you can create your HOCON `Config`
object in any other way and then parse it via
`HoconRaftConfigParser.parseConfig()`.

## Validation rules

`HoconRaftConfigParser` validates numeric config values strictly:

- fractional numeric values such as `1.5` are rejected,
- values for `int`-backed fields must stay within the Java `int` range,
- large integral values for `long`-backed fields are accepted.

[microraft-default.conf](https://github.com/MicroRaft/MicroRaft/blob/master/microraft-hocon/microraft-default.conf)
is the default MicroRaft HOCON configuration file.

Please refer to
[MicroRaft documentation page](https://microraft.io/docs/configuration/)
to learn more about configuration. 
