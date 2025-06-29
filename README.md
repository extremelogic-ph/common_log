## Deployment to Maven Central

### Prerequisites
1. Sonatype OSSRH account (for Maven Central deployment)
2. GPG installed and configured on your system
3. Maven 3.6.3 or higher
4. Valid GPG key pair (public and private keys)

### Setup

#### 1. Import GPG Keys
```bash
# Import your private key
gpg --import private.key

# Import your public key
gpg --import public.key

# Verify the keys were imported
gpg --list-secret-keys
gpg --list-public-keys
```

#### 2.  Fix GPG TTY Issues (if needed)

If you encounter the error gpg: signing failed: Inappropriate ioctl for device, run:

```
export GPG_TTY=$(tty)
```

#### 3. Configure Maven settings.xml

Add the following to your `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>ossrh</id>
      <username>your-sonatype-username</username>
      <password>your-sonatype-password</password>
    </server>
  </servers>
</settings>
```

#### 4. Deploy Command

`mvn clean deploy -Dgpg.passphrase=your_passphrase`


