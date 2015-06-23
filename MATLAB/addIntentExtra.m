function outCommand = addIntentExtra(inCommand, name, value)
% outString = addExtra(inString, name, value)
%
% Adds an Extra of the desired name and value to the existing command
% string. Used to construct Intents to send to devCam via the adb activity
% manager that include data.
%
% - - Input - - 
% inCommand : string indicating the shell command so far.
% name : string indicating the indicator of the value's purpose, must match
%           one that is expected by devCam in the device.
% value : string or integer of data to add to this command
%
% - - Output - -
% outCommand : string of the constructed command with data
% 
% 
% Rob Sumner - May 2015

if isnumeric(value)
    outCommand = fullcommand(inCommand,'--ei',name,num2str(value));
    
elseif ischar(value)
    outCommand = fullcommand(inCommand,'--es',name,value);
    
end


end % end function