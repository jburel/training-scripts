/*
 * -----------------------------------------------------------------------------
 *  Copyright (C) 2018 University of Dundee. All rights reserved.
 *
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * ------------------------------------------------------------------------------
 */

/*
 * This Groovy script downloads a file and opens it in ImageJ using Bio-Formats
 * exporter.
 * Use this script in the Scripting Dialog of Fiji (File > New > Script).
 * Select Groovy as language in the Scripting Dialog.
 * Error handling is omitted to ease the reading of the script but this
 * should be added if used in production to make sure the services are closed
 * Information can be found at
 * https://docs.openmicroscopy.org/latest/omero5/developers/Java.html
 */


import com.google.common.io.Files

// OMERO Dependencies
import omero.gateway.Gateway
import omero.gateway.LoginCredentials
import omero.gateway.SecurityContext
import omero.gateway.facility.TransferFacility
import omero.log.SimpleLogger

import ij.IJ


// Setup
// =====

// OMERO Server details
HOST = "outreach.openmicroscopy.org"
PORT = 4064
// parameters to edit
image_id = "1001"
USERNAME = "username"
PASSWORD = "password"


def connect_to_omero() {
    "Connect to OMERO"

    credentials = new LoginCredentials()
    credentials.getServer().setHostname(HOST)
    credentials.getServer().setPort(PORT)
    credentials.getUser().setUsername(USERNAME.trim())
    credentials.getUser().setPassword(PASSWORD.trim())
    simpleLogger = new SimpleLogger()
    gateway = new Gateway(simpleLogger)
    gateway.connect(credentials)
    return gateway

}

def download_image(gateway, image_id, path) {
    "Download the files composing the image"

    transfer = gateway.getFacility(TransferFacility)
    user = gateway.getLoggedInUser()
    ctx = new SecurityContext(user.getGroupId())
    return transfer.downloadImage(ctx, path, new Long(image_id))

} 

// Connect to OMERO
gateway = connect_to_omero()
// Download the image. This could be composed of several files
tmp_dir = Files.createTempDir()
files = download_image(gateway, image_id, tmp_dir.getAbsolutePath())

files.each() { f ->
    options = "open=" + f.getAbsolutePath()
    options += " autoscale color_mode=Default "
    options += "view=[Standard ImageJ] stack_order=Default"
    IJ.run("Bio-Formats Importer", options)
}

//Delete file in directory then delete it
tmp_dir.eachFile() { file ->
  file.delete()
}
tmp_dir.delete()
gateway.disconnect()
println("Disconnect")
