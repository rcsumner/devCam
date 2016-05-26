from numbers import Number
import subprocess as sp
import os

PLATFORM_TOOLS_DIR = '~/Developer/sdk/platform-tools/'
DEVICE_DEVCAM_DIR = '/storage/emulated/0/Pictures/devCam/'

#These are useful components of commands to pass through the adb shell
AM_PRE = 'am start -n com.devcam/.RemoteCaptureActivity' # common pre-amble for RemoteCapture commands
START_INTENT = '-a REMOTE_START'  # intent to start RemoteCapture preview window on device
CAPTURE_INTENT = '-a CAPTURE_REQUEST' # intent to initiate an actual capture on device



def adbShell(cmd):
    """
    Perform a command on the remote device using its shell via adb.

    Parameters
    ----------

    Returns
    -------
    flag : boolean indicating success or failure of command execution on the
            device

    Example
    -------
    To make the subdirectory Pictures/devCam/newDir on your device, simply use:
    adbshell('mkdir newDir')
    """

    # This ugly construct returns the command success state from the adb shell
    # as an output to the local system shell so we can see it.
    command = fullCommand(PLATFORM_TOOLS_DIR, 'adb shell ''',cmd,
        '> /dev/null 2>&1; echo $?''')

    # Have the local system's shell run this command in the adb shell
    try:
        result = sp.check_output(command,shell=True)
    except sp.CalledProcessError:
        # if the local system's command fails (non-0 status)
        print Exception('Error running adb.')
        return


    print 'result = ' + result
    # See if the output was the expected '0', indicating
    # a successful execution within the device's shell as well.
    return int(result)==0
        



def fullCommand(*args):
    """
    Correctly concatenates string inputs so that they form a valid command-line
    string command for working with the Android adb.

    Parameters
    ----------
    Any number of strings to concatenate

    Returns
    -------
    cmd : string correctly formatted with full path and command
    """

    if len(args)<2:
        raise ValueError('fullCommand() requires two or more string arguments.')

    # To combine strings, simply trim any whitespace before or after args, then
    # combine them in sequence with a single space in between. Note that this
    # actually (counter-intuitively) works when one of the arguments is just a 
    # space, since it gets trimmed to empty and then added with a space before
    # it. However, if a command piece is a path as indicated by a trailing '/'
    # or '\', then don't add a space between it and the next piece.
    # Skip empty strings.

    cmd = ''
    for arg in args:
        if len(arg)==0:
	    continue
	
	cmd = cmd + arg.strip()
        if not (arg[-1]=='/') and not (arg[-1]=='\\'):
            cmd = cmd + ' '
    return cmd[:-1] # strip last white space






def addIntentExtra(inCommand, name, value):
    """
    Adds an Extra of the desired name and value to the existing command string.
    Used to construct Intents that include data to send to devCam via the adb 
    activity manager.

    Parameters
    ----------
    inCommand : string, shell command constructed so far
    name : string, indicator of the value's purpose. Must match one that is 
            expected by devCam in the device
    value : string or int of data to add to this command

    Returns
    -------
    outCommand : string of the constructed command with data
    """

    if isinstance(value,Number):
        return fullCommand(inCommand, '--ei', name, str(value))

    if isinstance(value,basestring):
        return fullCommand(inCommand, '--es', name, value)



def fileExistsOnDevice(filename):
    """
    Checks if a file exists in the devCam directory on the attached device.
    Returns a boolean indicator of the file's existence.

    Parameters
    ----------
    filename : string indicating the filename and path relative to the devCam
                directory on the device

    Returns
    -------
    flag : boolean indicating file existence
    """

    filename = fullCommand(DEVICE_DEVCAM_DIR,filename)

    # Unfortunately, adb does not actually give feedback on the result of
    # commands run in its shell, so we have to do the following:
    # - in the adb shell, make a conditional which tests the existence of file
    # - echo the (in-adb) status of that check. '0' = run successfully = file exists
    # - parse the result of the system call to adb, which should contain this echoed value

    # Construct the command taking the adb shell form " ls file; echo $?" while sending the actual outputs to null (we only care about ls success/failure).
    # Note that as of Marshmallow, permissions seem to have changed in the adb shell, requiring the new use of 'run-as' to access the devCam output folder for this check.
    # This necessitates the use of quotes in the right place so that the success of ls (reported via stdout from echo) is reported, NOT the success of the run-as command.
    # Command will look something like:
    # ~/Developer/sdk/platform-tools/adb shell ' run-as com.devcam ls /storage/emulated/0/Pictures/devCam/cameraReport.json > /dev/null 2> /dev/null; echo $? '
    command = fullCommand('run-as com.devcam ls ',filename,'> /dev/null 2> /dev/null; echo $?');
    command = fullCommand(PLATFORM_TOOLS_DIR, "adb shell '",command,"'")
    
    # Have the local system's shell run this command in the adb shell
    try:
        result = sp.check_output(command,shell=True)
    except sp.CalledProcessError:
        # if the local system's command fails (non-0 status)
        print Exception('Error running adb.')
        return

    # result will be either '0\r\n' if file exists (successful ls) or '1\r\n' if not.
    return result[0]=='0'


def adbPush(localFile,remoteDir):
    """
    Pushes a file onto the device at the specified location using adb. If the
    remote target directory does not already exist, this tries to create it.

    Parameters
    ----------
    localFile : string of full filepath and name of local file
    remoteDir : string of path relative to main devCam directory on device.
                If pushing to main directory, just use ''.

    Returns
    -------
    flag : boolean indicating push success or failure

    Example
    -------
    To place ~/exposureBracket.json in the design directory on the device, use:
    adbPush('~/exposureBracket.json','Designs/')
    """

    # First, make sure the directory exists. This doesn't actually check
    # success because it cannot distinguish between failure due to the folder
    # existing already and failure do to some other cause. So it just assumes 
    # it worked, and an error should be caught by the subsequent command.
    mkdirCommand = fullCommand('mkdir', DEVICE_DEVCAM_DIR,remoteDir)
    adbShell(mkdirCommand)

    # Now actually push the file there
    cmd = fullCommand(PLATFORM_TOOLS_DIR,'adb push',
            localFile,DEVICE_DEVCAM_DIR,remoteDir)
    return sp.call(command,shell=True)



def adbPull(remoteFile,localDir):
    """
    Pulls a file from the attached Android device onto the local machine. If
    the requested local directory does not already exist, this creates it.

    Parameters
    ----------
    remoteFile : string of full file name and filepath relative to main devCam
                    directory on the remote device
    localDir : string path of location to put file on local machine. To use the
                    current working directory use ''.

    Returns
    -------
    flag : boolean indicating pull success or failure
    """

    if not os.path.isdir(localDir):
        os.makedirs(localDir)

    command = fullCommand(PLATFORM_TOOLS_DIR,'adb pull',
        DEVICE_DEVCAM_DIR,remoteFile,localDir)
    return sp.call(command,shell=True)


def lsDevice(subpath=''):
    """
    Print the list of files in the devCam base folder on the device, or in a sub-directory of it.

    Parameters
    ----------
    subpath (optional) : A subpath relative to the main devCam folder on device.
    """
    command = fullCommand(PLATFORM_TOOLS_DIR,'adb shell run-as com.devcam ls',DEVICE_DEVCAM_DIR,subpath)
    sp.call(command,shell=True)


def startRemotePreview():
    """
    Start the devCam remote capture activity on the attached Android device, 
    showing a preview window on its screen. Further capture commands can then
    be sent via requestRemoteCapture(). 

    Note it is not necessary to use this function prior to 
    requestRemoteCapture(), but it helps. :-)

    Returns
    -------
    flag : boolean indicating if devCam opened on the device successfully
    """

    #AM_PRE contains the Activity Manager adb call necessary to open activity
    return adbShell(AM_PRE)


def requestRemoteCapture(captureDesign,designName,format,dims,processing,targetDir):
    """

    Parameters
    ----------
    captureDesign : sequence of 
    designName : 
    format : 
    dims :
    processing :
    targetDir : 
    """










