/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.meshy;

import java.io.File;

import java.util.Iterator;


public interface LocalFileHandler {

    public boolean canHandleDirectory(File dir);

    public Iterator<VirtualFileReference> listFiles(File dir, VirtualFileFilter filter);

    public VirtualFileReference getFile(File dir, String name);
}
