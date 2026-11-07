package com.specdriven.regression.runner;
import java.io.IOException;
import java.nio.file.Path;
/** Filesystem durability boundary; unsupported force/readback is a failed SAVE. */
interface RunnerDurability {
    void forceDirectory(Path directory)throws IOException;
    void forceFile(Path file)throws IOException;
    default byte[] readSaved(Path root,String ref,long maximum)throws IOException{return RunnerFiles.read(root,ref,maximum);}
    RunnerDurability SYSTEM=new RunnerDurability(){
        public void forceDirectory(Path directory)throws IOException{RunnerFiles.sync(directory);}
        public void forceFile(Path file)throws IOException{RunnerFiles.forceFile(file);}
    };
}
