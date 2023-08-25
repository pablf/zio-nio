package zio.nio.file

import java.nio.file.{Files, SimpleFileVisitor, Path => JPath}
import java.io.IOException
import zio.{Trace, ZIO}
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitor

trait FilesPlatformSpecific { self =>
    def deleteRecursive(path: Path)(implicit trace: Trace): ZIO[Any, IOException, Long] =
    ZIO.attempt {
      val visitator: FileVisitor[JPath] = new SimpleFileVisitor[JPath]() {
        
        override def visitFile(file: JPath, attrs: BasicFileAttributes) = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: JPath, exc: IOException) =
          FileVisitResult.CONTINUE
        
      }
      Files.walkFileTree(path.javaPath, visitator)
      0L
    }.refineToOrDie[IOException]
}
