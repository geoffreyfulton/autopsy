/*
 * 
 * Autopsy Forensic Browser
 * 
 * Copyright 2018 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.commonfilesearch;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.datamodel.TskCoreException;

/**
 *
 * @author bsweeney
 */
abstract class ICommonFilesMetadataBuilder {
    
    abstract CommonFilesMetadata findFiles() throws TskCoreException, NoCurrentCaseException, SQLException, Exception;
    
    abstract String buildTabTitle();
    
    abstract String buildCategorySelectionString();
    
    static Map<Integer, List<Md5Metadata>> collateMatchesByNumberOfInstances(Map<String, Md5Metadata> commonFiles) {
        //collate matches by number of matching instances - doing this in sql doesnt seem efficient
        Map<Integer, List<Md5Metadata>> instanceCollatedCommonFiles = new TreeMap<>();
        for(Md5Metadata md5Metadata : commonFiles.values()){
            Integer size = md5Metadata.size();
            
            if(instanceCollatedCommonFiles.containsKey(size)){
                instanceCollatedCommonFiles.get(size).add(md5Metadata);
            } else {
                ArrayList<Md5Metadata> value = new ArrayList<>();
                value.add(md5Metadata);
                instanceCollatedCommonFiles.put(size, value);
            }
        }
        return instanceCollatedCommonFiles;
    }
    
    /*
     * The set of the MIME types that will be checked for extension mismatches
     * when checkType is ONLY_MEDIA.
     * ".jpg", ".jpeg", ".png", ".psd", ".nef", ".tiff", ".bmp", ".tec"
     * ".aaf", ".3gp", ".asf", ".avi", ".m1v", ".m2v", //NON-NLS
     * ".m4v", ".mp4", ".mov", ".mpeg", ".mpg", ".mpe", ".mp4", ".rm", ".wmv", ".mpv", ".flv", ".swf"
     */
    static final Set<String> MEDIA_PICS_VIDEO_MIME_TYPES = Stream.of(
            "image/bmp", //NON-NLS
            "image/gif", //NON-NLS
            "image/jpeg", //NON-NLS
            "image/png", //NON-NLS
            "image/tiff", //NON-NLS
            "image/vnd.adobe.photoshop", //NON-NLS
            "image/x-raw-nikon", //NON-NLS
            "image/x-ms-bmp", //NON-NLS
            "image/x-icon", //NON-NLS
            "video/webm", //NON-NLS
            "video/3gpp", //NON-NLS
            "video/3gpp2", //NON-NLS
            "video/ogg", //NON-NLS
            "video/mpeg", //NON-NLS
            "video/mp4", //NON-NLS
            "video/quicktime", //NON-NLS
            "video/x-msvideo", //NON-NLS
            "video/x-flv", //NON-NLS
            "video/x-m4v", //NON-NLS
            "video/x-ms-wmv", //NON-NLS
            "application/vnd.ms-asf", //NON-NLS
            "application/vnd.rn-realmedia", //NON-NLS
            "application/x-shockwave-flash" //NON-NLS
    ).collect(Collectors.toSet());

    /*
     * The set of the MIME types that will be checked for extension mismatches
     * when checkType is ONLY_TEXT_FILES.
     * ".doc", ".docx", ".odt", ".xls", ".xlsx", ".ppt", ".pptx"
     * ".txt", ".rtf", ".log", ".text", ".xml"
     * ".html", ".htm", ".css", ".js", ".php", ".aspx"
     * ".pdf"
     */
    static final Set<String> TEXT_FILES_MIME_TYPES = Stream.of(
            "text/plain", //NON-NLS
            "application/rtf", //NON-NLS
            "application/pdf", //NON-NLS
            "text/css", //NON-NLS
            "text/html", //NON-NLS
            "text/csv", //NON-NLS
            "application/json", //NON-NLS
            "application/javascript", //NON-NLS
            "application/xml", //NON-NLS
            "text/calendar", //NON-NLS
            "application/x-msoffice", //NON-NLS
            "application/x-ooxml", //NON-NLS
            "application/msword", //NON-NLS
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", //NON-NLS
            "application/vnd.ms-powerpoint", //NON-NLS
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", //NON-NLS
            "application/vnd.ms-excel", //NON-NLS
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", //NON-NLS
            "application/vnd.oasis.opendocument.presentation", //NON-NLS
            "application/vnd.oasis.opendocument.spreadsheet", //NON-NLS
            "application/vnd.oasis.opendocument.text" //NON-NLS
    ).collect(Collectors.toSet());
}
